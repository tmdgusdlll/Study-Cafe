# 상점 + 포인트 차감/이력 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 포인트 시스템에 차감(spend)과 이력 조회를 추가하고, 포인트로 아이템을 구매하는 상점 도메인을 구축한다.

**Architecture:** 엔티티는 JPA 연관관계 대신 FK(Long id)만 보유한다. 구매는 `StoreService`가 `PointService.spend()`를 호출해 포인트를 차감하고 `UserItem`을 저장하는 단일 트랜잭션으로 처리한다. 목록/보유 아이템 조회는 `findAllById()` 배치 조회로 N+1을 방지한다. 컨트롤러는 `@AuthenticationPrincipal Long memberId`로 인증 주체를 받는다(요청 파라미터로 memberId를 신뢰하지 않는다).

**Tech Stack:** Spring Boot 4.1.0, Java 17, PostgreSQL, Spring Data JPA, Testcontainers

## Global Constraints

- 언어: Java 17, Jakarta EE (`import jakarta.*`)
- 패키지 루트: `com.studycafe`
- 주석: 한글
- DB: PostgreSQL (`localhost:5432`, db=`studycafe`, user=`studycafe`, pw=`studycafe`)
- 테스트 명령: `./gradlew test --tests "패키지.클래스명"`
- Lombok: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@RequiredArgsConstructor`, 정적 팩토리 메서드
- Entity 패턴: `@GeneratedValue(strategy = GenerationType.IDENTITY)`, protected 기본 생성자, 타임스탬프는 **수동 설정**(JPA Auditing 미사용)
- DTO: Java **record** 사용
- API 응답: `ResponseEntity<ApiResponse<T>>` 래퍼 사용, 컨트롤러 경로 `/api/v1/...`
- 예외: 도메인 예외 없이 `CustomException(ErrorCode)` 직접 사용, `GlobalExceptionHandler`가 처리
- 인증: 컨트롤러는 `@AuthenticationPrincipal Long memberId` 사용 (JwtAuthenticationFilter가 principal로 `Long` 주입)
- 통합 테스트: `IntegrationTestSupport`(추상 클래스) 상속, `@Autowired` 실제 빈, `@AfterEach`에서 `deleteAll()`
- enum 네이밍: 기존 `PointTransactionType` 그대로 사용 (`SESSION_EARN`, `PAYMENT_CHARGE`, `ITEM_PURCHASE`)

## 현재 코드 기준 (이미 존재 — 재생성 금지)

- `PointTransactionType` = `{ SESSION_EARN, PAYMENT_CHARGE, ITEM_PURCHASE }` — 그대로 사용
- `UserPoint` — `id, memberId, balance, @Version version`, 메서드 `of(memberId)`, `earn(amount)` (spend 없음 → Task A에서 추가)
- `PointHistory` — `id, memberId, long amount, type, createdAt`, `of(memberId, amount, type)` (createdAt 수동)
- `UserPointRepository.findByMemberId(Long): Optional<UserPoint>`
- `PointHistoryRepository` — 조회 메서드 없음 (Task A에서 추가)
- `PointService` — 클래스 레벨 `@Transactional`, `earn(memberId, amount, type)`, `getBalance(memberId): PointBalanceResponse` (member 검증 없음)
- `PointController` — `GET /api/v1/points/balance`, `@AuthenticationPrincipal Long memberId`, `ResponseEntity<ApiResponse<...>>`
- `PointBalanceResponse(Long memberId, Long balance)` — record, accessor `balance()`

---

### Task A: 포인트 차감(spend) + 이력 조회 API

**Files:**
- Modify: `src/main/java/com/studycafe/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/studycafe/domain/point/entity/UserPoint.java`
- Modify: `src/main/java/com/studycafe/domain/point/repository/PointHistoryRepository.java`
- Create: `src/main/java/com/studycafe/domain/point/dto/PointHistoryResponse.java`
- Modify: `src/main/java/com/studycafe/domain/point/service/PointService.java`
- Modify: `src/main/java/com/studycafe/domain/point/controller/PointController.java`
- Test: `src/test/java/com/studycafe/domain/point/service/PointServiceTest.java` (기존 파일에 테스트 추가)

**Interfaces:**
- Consumes: `UserPointRepository.findByMemberId(Long)`, `PointHistoryRepository.save(PointHistory)`, `PointHistory.of(Long, long, PointTransactionType)`
- Produces:
  - `UserPoint.spend(long amount)` → `void` (잔액 부족 시 `CustomException(INSUFFICIENT_POINTS)`)
  - `PointService.spend(Long memberId, long amount, PointTransactionType type)` → `void`
  - `PointService.getHistory(Long memberId)` → `List<PointHistoryResponse>` (최신순)
  - `PointHistoryResponse(long amount, PointTransactionType type, LocalDateTime createdAt)` — record, `from(PointHistory)`
  - `PointHistoryRepository.findByMemberIdOrderByIdDesc(Long)` → `List<PointHistory>`
  - `GET /api/v1/points/history` → `ApiResponse<List<PointHistoryResponse>>`

- [ ] **Step 1: ErrorCode에 INSUFFICIENT_POINTS 추가**

기존 세션 섹션 끝(`SESSION_ALREADY_IN_PROGRESS` 줄)의 세미콜론을 콤마로 바꾸고 포인트 섹션을 추가한다.

```java
    // 세션
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "진행 중인 세션을 찾을 수 없습니다"),
    SESSION_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 세션이 있습니다"),

    // 포인트
    INSUFFICIENT_POINTS(HttpStatus.BAD_REQUEST, "포인트가 부족합니다");
```

- [ ] **Step 2: UserPoint에 spend() 추가**

`import`에 예외 클래스를 추가하고 `earn()` 아래에 `spend()`를 추가한다.

```java
package com.studycafe.domain.point.entity;

import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_points")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(nullable = false)
    private Long balance = 0L;

    @Version
    private Long version;

    public static UserPoint of(Long memberId) {
        UserPoint userPoint = new UserPoint();
        userPoint.memberId = memberId;
        userPoint.balance = 0L;
        return userPoint;
    }

    public void earn(long amount) {
        this.balance += amount;
    }

    // 잔액 차감. 잔액이 부족하면 예외를 던진다.
    public void spend(long amount) {
        if (this.balance < amount) {
            throw new CustomException(ErrorCode.INSUFFICIENT_POINTS);
        }
        this.balance -= amount;
    }
}
```

- [ ] **Step 3: PointHistoryRepository에 최신순 조회 추가**

`createdAt`은 수동 설정이라 동일 밀리초 충돌 가능성이 있으므로, 단조 증가하는 `id` 기준 내림차순으로 최신순을 보장한다.

```java
package com.studycafe.domain.point.repository;

import com.studycafe.domain.point.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    List<PointHistory> findByMemberIdOrderByIdDesc(Long memberId);
}
```

- [ ] **Step 4: PointHistoryResponse record 작성**

```java
package com.studycafe.domain.point.dto;

import com.studycafe.domain.point.entity.PointHistory;
import com.studycafe.domain.point.entity.PointTransactionType;

import java.time.LocalDateTime;

public record PointHistoryResponse(long amount, PointTransactionType type, LocalDateTime createdAt) {

    public static PointHistoryResponse from(PointHistory history) {
        return new PointHistoryResponse(history.getAmount(), history.getType(), history.getCreatedAt());
    }
}
```

- [ ] **Step 5: PointServiceTest에 실패 테스트 추가**

기존 `PointServiceTest`(통합 테스트, `IntegrationTestSupport` 상속)에 import와 테스트 4개를 추가한다. `earn`/`spend`는 member 검증이 없으므로 실제 Member 없이 memberId만으로 동작한다.

```java
package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.dto.PointHistoryResponse;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointServiceTest extends IntegrationTestSupport {

    @Autowired
    private PointService pointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void tearDown() {
        pointHistoryRepository.deleteAll();
        userPointRepository.deleteAll();
    }

    @Test
    void 포인트_적립_성공() {
        pointService.earn(10L, 100L, PointTransactionType.SESSION_EARN);

        PointBalanceResponse balance = pointService.getBalance(10L);
        assertThat(balance.balance()).isEqualTo(100L);
    }

    @Test
    void 포인트가_없는_회원_잔액_조회_시_0반환() {
        PointBalanceResponse balance = pointService.getBalance(999L);
        assertThat(balance.balance()).isEqualTo(0L);
    }

    @Test
    void 포인트_누적_적립() {
        pointService.earn(20L, 50L, PointTransactionType.SESSION_EARN);
        pointService.earn(20L, 30L, PointTransactionType.SESSION_EARN);

        PointBalanceResponse balance = pointService.getBalance(20L);
        assertThat(balance.balance()).isEqualTo(80L);
    }

    @Test
    void 포인트_차감_성공() {
        pointService.earn(30L, 100L, PointTransactionType.SESSION_EARN);

        pointService.spend(30L, 40L, PointTransactionType.ITEM_PURCHASE);

        assertThat(pointService.getBalance(30L).balance()).isEqualTo(60L);
    }

    @Test
    void 잔액_부족_시_차감_실패() {
        pointService.earn(31L, 10L, PointTransactionType.SESSION_EARN);

        assertThatThrownBy(() -> pointService.spend(31L, 50L, PointTransactionType.ITEM_PURCHASE))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());
    }

    @Test
    void 포인트_없는_회원_차감_실패() {
        assertThatThrownBy(() -> pointService.spend(999L, 10L, PointTransactionType.ITEM_PURCHASE))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());
    }

    @Test
    void 포인트_이력_최신순_조회() {
        pointService.earn(40L, 100L, PointTransactionType.SESSION_EARN);
        pointService.spend(40L, 30L, PointTransactionType.ITEM_PURCHASE);

        List<PointHistoryResponse> history = pointService.getHistory(40L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).amount()).isEqualTo(-30L); // 최신(차감)이 먼저
        assertThat(history.get(1).amount()).isEqualTo(100L);
    }
}
```

- [ ] **Step 6: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.point.service.PointServiceTest"
```

기대 결과: FAIL — `PointService.spend`, `PointService.getHistory`, `PointHistoryResponse` 없음(컴파일 에러)

- [ ] **Step 7: PointService에 spend() / getHistory() 구현**

기존 `PointService`에 import와 두 메서드를 추가한다. 클래스 레벨 `@Transactional`은 유지된다.

```java
package com.studycafe.domain.point.service;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.dto.PointHistoryResponse;
import com.studycafe.domain.point.entity.PointHistory;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.entity.UserPoint;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PointService {

    private final UserPointRepository userPointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    public void earn(Long memberId, long amount, PointTransactionType type) {
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseGet(() -> userPointRepository.save(UserPoint.of(memberId)));
        userPoint.earn(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, amount, type));
    }

    public void spend(Long memberId, long amount, PointTransactionType type) {
        // 잔액이 없는(UserPoint 미존재) 회원은 곧 잔액 0이므로 부족 예외로 처리한다.
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.INSUFFICIENT_POINTS));
        userPoint.spend(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, -amount, type));
    }

    @Transactional(readOnly = true)
    public PointBalanceResponse getBalance(Long memberId) {
        long balance = userPointRepository.findByMemberId(memberId)
                .map(UserPoint::getBalance)
                .orElse(0L);
        return new PointBalanceResponse(memberId, balance);
    }

    @Transactional(readOnly = true)
    public List<PointHistoryResponse> getHistory(Long memberId) {
        return pointHistoryRepository.findByMemberIdOrderByIdDesc(memberId).stream()
                .map(PointHistoryResponse::from)
                .toList();
    }
}
```

- [ ] **Step 8: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.point.service.PointServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 7 tests passed

- [ ] **Step 9: PointController에 이력 조회 엔드포인트 추가**

기존 `PointController`에 import와 `getHistory` 핸들러를 추가한다.

```java
package com.studycafe.domain.point.controller;

import com.studycafe.domain.point.dto.PointBalanceResponse;
import com.studycafe.domain.point.dto.PointHistoryResponse;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<PointBalanceResponse>> getBalance(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(pointService.getBalance(memberId)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<PointHistoryResponse>>> getHistory(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(pointService.getHistory(memberId)));
    }
}
```

- [ ] **Step 10: 전체 테스트로 회귀 확인**

```bash
./gradlew test
```

기대 결과: `BUILD SUCCESSFUL`, 기존 통과 테스트 + 신규 spend/history 테스트 모두 통과

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/studycafe/domain/point src/main/java/com/studycafe/global/exception/ErrorCode.java src/test/java/com/studycafe/domain/point/service/PointServiceTest.java
git commit -m "feat: 포인트 차감(spend) + 이력 조회 API 추가"
```

---

### Task B: 상점 도메인 (아이템 목록/구매/보유)

**Files:**
- Modify: `src/main/java/com/studycafe/global/exception/ErrorCode.java`
- Create: `src/main/java/com/studycafe/domain/store/entity/ItemCategory.java`
- Create: `src/main/java/com/studycafe/domain/store/entity/StoreItem.java`
- Create: `src/main/java/com/studycafe/domain/store/entity/UserItem.java`
- Create: `src/main/java/com/studycafe/domain/store/repository/StoreItemRepository.java`
- Create: `src/main/java/com/studycafe/domain/store/repository/UserItemRepository.java`
- Create: `src/main/java/com/studycafe/domain/store/dto/StoreItemResponse.java`
- Create: `src/main/java/com/studycafe/domain/store/service/StoreService.java`
- Create: `src/main/java/com/studycafe/domain/store/controller/StoreController.java`
- Test: `src/test/java/com/studycafe/domain/store/service/StoreServiceTest.java`

**Interfaces:**
- Consumes: `PointService.spend(Long, long, PointTransactionType)` (Task A), `PointTransactionType.ITEM_PURCHASE`
- Produces:
  - `StoreItem.of(String name, String description, ItemCategory category, Long price, String resourcePath)` → `StoreItem`
  - `UserItem.of(Long memberId, Long storeItemId)` → `UserItem`
  - `StoreItemRepository.findAllById(Iterable<Long>)`, `findAll()`, `findById(Long)`
  - `UserItemRepository.existsByMemberIdAndStoreItemId(Long, Long)` → `boolean`, `findByMemberId(Long)` → `List<UserItem>`
  - `StoreItemResponse(Long id, String name, String description, ItemCategory category, Long price, String resourcePath)` — record, `from(StoreItem)`
  - `StoreService.getItems()` → `List<StoreItemResponse>`
  - `StoreService.getMyItems(Long memberId)` → `List<StoreItemResponse>`
  - `StoreService.purchase(Long memberId, Long itemId)` → `void`
  - `GET /api/v1/store/items`, `GET /api/v1/store/my-items`, `POST /api/v1/store/items/{itemId}/purchase`

- [ ] **Step 1: ErrorCode에 상점 에러 추가**

Task A에서 추가한 포인트 섹션 뒤에 상점 섹션을 추가한다(`INSUFFICIENT_POINTS` 줄의 세미콜론을 콤마로).

```java
    // 포인트
    INSUFFICIENT_POINTS(HttpStatus.BAD_REQUEST, "포인트가 부족합니다"),

    // 상점
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다"),
    ITEM_ALREADY_OWNED(HttpStatus.CONFLICT, "이미 보유한 아이템입니다");
```

- [ ] **Step 2: ItemCategory enum 작성**

```java
package com.studycafe.domain.store.entity;

public enum ItemCategory {
    BACKGROUND,  // 카페 배경 테마
    SOUND,       // 앰비언트 사운드 팩
    NPC          // NPC 캐릭터 팩
}
```

- [ ] **Step 3: StoreItem 엔티티 작성**

```java
package com.studycafe.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemCategory category;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    private String resourcePath;

    public static StoreItem of(String name, String description, ItemCategory category,
                               Long price, String resourcePath) {
        StoreItem item = new StoreItem();
        item.name = name;
        item.description = description;
        item.category = category;
        item.price = price;
        item.resourcePath = resourcePath;
        return item;
    }
}
```

- [ ] **Step 4: UserItem 엔티티 작성 — FK만 보유, 타임스탬프 수동 설정**

JPA Auditing을 사용하지 않으므로 `@CreatedDate` 대신 `of()`에서 `purchasedAt`을 직접 설정한다(기존 `PointHistory`와 동일 패턴).

```java
package com.studycafe.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "store_item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "store_item_id", nullable = false)
    private Long storeItemId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    public static UserItem of(Long memberId, Long storeItemId) {
        UserItem userItem = new UserItem();
        userItem.memberId = memberId;
        userItem.storeItemId = storeItemId;
        userItem.purchasedAt = LocalDateTime.now();
        return userItem;
    }
}
```

- [ ] **Step 5: Repository 두 개 작성**

```java
package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.StoreItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreItemRepository extends JpaRepository<StoreItem, Long> {
}
```

```java
package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.UserItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {

    boolean existsByMemberIdAndStoreItemId(Long memberId, Long storeItemId);

    List<UserItem> findByMemberId(Long memberId);
}
```

- [ ] **Step 6: StoreItemResponse record 작성**

```java
package com.studycafe.domain.store.dto;

import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;

public record StoreItemResponse(Long id, String name, String description,
                                ItemCategory category, Long price, String resourcePath) {

    public static StoreItemResponse from(StoreItem item) {
        return new StoreItemResponse(item.getId(), item.getName(), item.getDescription(),
                item.getCategory(), item.getPrice(), item.getResourcePath());
    }
}
```

- [ ] **Step 7: 실패하는 StoreServiceTest 작성 (통합 테스트)**

`earn`/`spend`가 member 검증을 하지 않으므로 실제 Member 없이 memberId만으로 테스트한다. 구매 실패 시 `@Transactional` 롤백으로 아이템이 저장되지 않음을 함께 검증한다.

```java
package com.studycafe.domain.store.service;

import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;
import com.studycafe.domain.store.repository.StoreItemRepository;
import com.studycafe.domain.store.repository.UserItemRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreServiceTest extends IntegrationTestSupport {

    @Autowired
    private StoreService storeService;

    @Autowired
    private StoreItemRepository storeItemRepository;

    @Autowired
    private UserItemRepository userItemRepository;

    @Autowired
    private PointService pointService;

    @Autowired
    private UserPointRepository userPointRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void tearDown() {
        userItemRepository.deleteAll();
        storeItemRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        userPointRepository.deleteAll();
    }

    private StoreItem saveItem(long price) {
        return storeItemRepository.save(
                StoreItem.of("빈티지 카페", "따뜻한 빈티지 분위기", ItemCategory.BACKGROUND, price, "/bg/vintage.png"));
    }

    @Test
    void 구매_성공_포인트차감_아이템저장() {
        StoreItem item = saveItem(500L);
        pointService.earn(1L, 1000L, PointTransactionType.SESSION_EARN);

        storeService.purchase(1L, item.getId());

        assertThat(pointService.getBalance(1L).balance()).isEqualTo(500L);
        assertThat(userItemRepository.existsByMemberIdAndStoreItemId(1L, item.getId())).isTrue();
    }

    @Test
    void 포인트_부족_시_구매_실패하고_아이템_미저장() {
        StoreItem item = saveItem(500L);
        pointService.earn(2L, 100L, PointTransactionType.SESSION_EARN);

        assertThatThrownBy(() -> storeService.purchase(2L, item.getId()))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.INSUFFICIENT_POINTS.getMessage());

        assertThat(userItemRepository.existsByMemberIdAndStoreItemId(2L, item.getId())).isFalse();
    }

    @Test
    void 이미_보유한_아이템_구매_실패() {
        StoreItem item = saveItem(300L);
        pointService.earn(3L, 1000L, PointTransactionType.SESSION_EARN);
        storeService.purchase(3L, item.getId());

        assertThatThrownBy(() -> storeService.purchase(3L, item.getId()))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.ITEM_ALREADY_OWNED.getMessage());
    }

    @Test
    void 존재하지_않는_아이템_구매_실패() {
        assertThatThrownBy(() -> storeService.purchase(4L, 999L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.ITEM_NOT_FOUND.getMessage());
    }
}
```

- [ ] **Step 8: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.store.service.StoreServiceTest"
```

기대 결과: FAIL — `StoreService` 클래스 없음

- [ ] **Step 9: StoreService 구현**

`PointService`와 동일하게 member 검증은 하지 않는다(memberId는 인증 principal에서 오며, 기존 포인트 서비스와 일관). 구매는 아이템 조회 → 보유 확인 → 포인트 차감 → 저장 순서의 단일 트랜잭션이다.

```java
package com.studycafe.domain.store.service;

import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.entity.StoreItem;
import com.studycafe.domain.store.entity.UserItem;
import com.studycafe.domain.store.repository.StoreItemRepository;
import com.studycafe.domain.store.repository.UserItemRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreItemRepository storeItemRepository;
    private final UserItemRepository userItemRepository;
    private final PointService pointService;

    @Transactional(readOnly = true)
    public List<StoreItemResponse> getItems() {
        return storeItemRepository.findAll().stream()
                .map(StoreItemResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoreItemResponse> getMyItems(Long memberId) {
        // 1) 내가 보유한 storeItemId 목록
        List<Long> storeItemIds = userItemRepository.findByMemberId(memberId).stream()
                .map(UserItem::getStoreItemId)
                .toList();
        // 2) findAllById 배치 조회 — 쿼리 1번으로 N+1 방지
        return storeItemRepository.findAllById(storeItemIds).stream()
                .map(StoreItemResponse::from)
                .toList();
    }

    @Transactional
    public void purchase(Long memberId, Long itemId) {
        StoreItem item = storeItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException(ErrorCode.ITEM_NOT_FOUND));

        if (userItemRepository.existsByMemberIdAndStoreItemId(memberId, itemId)) {
            throw new CustomException(ErrorCode.ITEM_ALREADY_OWNED);
        }

        pointService.spend(memberId, item.getPrice(), PointTransactionType.ITEM_PURCHASE);
        userItemRepository.save(UserItem.of(memberId, itemId));
    }
}
```

- [ ] **Step 10: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.store.service.StoreServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 11: StoreController 구현**

memberId는 요청 파라미터가 아니라 `@AuthenticationPrincipal`로 받는다(인증된 주체만 자신의 자원에 접근).

```java
package com.studycafe.domain.store.controller;

import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.service.StoreService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<StoreItemResponse>>> getItems() {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getItems()));
    }

    @GetMapping("/my-items")
    public ResponseEntity<ApiResponse<List<StoreItemResponse>>> getMyItems(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.ok(storeService.getMyItems(memberId)));
    }

    @PostMapping("/items/{itemId}/purchase")
    public ResponseEntity<ApiResponse<Void>> purchase(
            @AuthenticationPrincipal Long memberId, @PathVariable Long itemId) {
        storeService.purchase(memberId, itemId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
```

- [ ] **Step 12: 전체 테스트로 회귀 확인**

```bash
./gradlew test
```

기대 결과: `BUILD SUCCESSFUL`, 전체 테스트 통과 (Task A 7 + Store 4 + 기존 테스트)

- [ ] **Step 13: 커밋**

```bash
git add src/main/java/com/studycafe/domain/store src/main/java/com/studycafe/global/exception/ErrorCode.java src/test/java/com/studycafe/domain/store
git commit -m "feat: 상점 도메인 구축 (아이템 목록/구매/보유, 포인트 차감 연동)"
```

---

## 보류: Task C — 포트원 V2 결제 연동

포트원 API 키/가맹점이 준비되면 별도 진행한다. 원본 계획(`2026-07-06-points-portone.md` Task 6)을 아래 조정사항과 함께 사용한다.

- **HTTP 클라이언트**: 원본의 `WebClient`(WebFlux 의존성) 대신 **Boot 4 내장 `RestClient`** 사용 (webmvc 스택 유지, 추가 의존성 없음)
- **enum**: 충전은 기존 `PointTransactionType.PAYMENT_CHARGE` 사용
- **충전 반영**: 웹훅 검증·멱등성 처리 후 `PointService.earn(memberId, amount, PAYMENT_CHARGE)` 호출
- **ErrorCode 추가 필요**: `DUPLICATE_PAYMENT`, `PAYMENT_NOT_FOUND`, `PAYMENT_VERIFICATION_FAILED`
- **시크릿 관리**: 포트원 API secret은 `.env`(gitignore)로 주입, `application.yaml`은 `${PORTONE_API_SECRET}` 참조(기본값 없이 fail-fast). `.env.example`에 키 항목 추가
- **컨트롤러**: `@AuthenticationPrincipal Long memberId` 사용 (원본의 `@RequestParam memberId` 지양)

---

## Self-Review

### 1. Spec 커버리지

| 요구사항 | 담당 |
|---|---|
| 포인트 차감(spend) | Task A Step 2·7 ✅ |
| 잔액 부족 예외 | Task A Step 1·2 (`INSUFFICIENT_POINTS`) ✅ |
| 포인트 이력 조회 API | Task A Step 3·4·9 (`GET /points/history`) ✅ |
| 상점 아이템 목록 | Task B Step 9·11 (`GET /store/items`) ✅ |
| 아이템 구매(포인트 차감) | Task B Step 9 (`purchase` → `spend`) ✅ |
| 보유 아이템 조회 | Task B Step 9·11 (`GET /store/my-items`, `findAllById` 배치) ✅ |
| 중복 구매 방지 | Task B Step 4(유니크 제약)·9(`ITEM_ALREADY_OWNED`) ✅ |
| 포트원 충전 | 보류(Task C) — 키 준비 후 |

### 2. 타입 일관성

- `PointService.spend(Long, long, PointTransactionType)` — Task A 정의, Task B `StoreService.purchase`에서 동일 호출 ✅
- `PointTransactionType.ITEM_PURCHASE` — 기존 enum 값, Task A 테스트·Task B 서비스 동일 사용 ✅
- `StoreItemResponse.from(StoreItem)` — Task B Step 6 정의, Service·Controller 동일 사용 ✅
- `@AuthenticationPrincipal Long memberId` — 기존 컨트롤러와 동일, 신규 엔드포인트 모두 동일 ✅
- DTO는 모두 record (`PointHistoryResponse`, `StoreItemResponse`) — 기존 `PointBalanceResponse` 컨벤션 일치 ✅

### 3. 알려진 함정

- **JPA Auditing 미설정**: `@CreatedDate` 사용 금지, 타임스탬프는 `of()`에서 수동 설정 (Task B Step 4)
- **이력 최신순**: 수동 `createdAt` 밀리초 충돌 방지 위해 `OrderByIdDesc` 사용 (Task A Step 3)
- **member 검증 없음**: 기존 `PointService`와 일관되게 `StoreService`도 member 존재 검증을 하지 않음 (인증 principal 신뢰)
- **상점 아이템 시드**: 상점에는 아이템 등록 API가 없다. 수동 확인 시 `StoreItem`을 DB에 직접 insert하거나 `data.sql`로 시드해야 한다(테스트는 리포지토리로 자체 생성).
