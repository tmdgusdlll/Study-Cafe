# 포인트 & 포트원 결제 시스템 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공부 세션 달성 시 포인트를 적립하고, 포트원으로 포인트를 직접 충전하며, 적립된 포인트로 상점 아이템을 구매하는 시스템 구축

**Architecture:** 엔티티는 JPA 연관관계 대신 FK(Long id)만 보유한다. 포인트 잔액에 낙관적 락(@Version)을 적용해 동시성을 보장하고, 포트원 웹훅은 멱등성 처리로 중복 적립을 방지한다. 연관 데이터 조회가 필요한 경우 `findAllById()`로 배치 조회해 N+1을 방지한다.

**Tech Stack:** Spring Boot 4.1.0, Java 17, PostgreSQL, Spring Data JPA, Spring WebFlux(WebClient), PortOne V2 REST API, Lombok

## Global Constraints

- 언어: Java 17, Jakarta EE (`import jakarta.*`)
- 패키지 루트: `com.studycafe`
- 주석: 한글
- DB: PostgreSQL (`localhost:5432`, db=`studycafe`, user=`studycafe`, pw=`studycafe`)
- 테스트 명령: `./gradlew test --tests "패키지.클래스명"`
- Lombok: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@RequiredArgsConstructor`
- Entity 패턴: 정적 팩토리 메서드, protected 기본 생성자
- API 응답: `ApiResponse<T>` 래퍼 사용
- 예외: `CustomException(ErrorCode)` 사용

---

### Task 1: PostgreSQL 환경 설정

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: PostgreSQL 연결 성공, `./gradlew bootRun` 정상 실행

- [ ] **Step 1: build.gradle 수정 — MySQL 제거, PostgreSQL + WebFlux 추가**

```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com'
version = '0.0.1-SNAPSHOT'
description = 'Study-Cafe'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-kafka'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-webflux'  // 포트원 WebClient용
    compileOnly 'org.projectlombok:lombok'
    runtimeOnly 'org.postgresql:postgresql'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testCompileOnly 'org.projectlombok:lombok'
    testAnnotationProcessor 'org.projectlombok:lombok'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 2: application.yaml 전체 교체**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/studycafe
    username: studycafe
    password: studycafe
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
  security:
    user:
      name: admin
      password: admin

portone:
  api-secret: ${PORTONE_API_SECRET:test-secret}
  base-url: https://api.portone.io
```

- [ ] **Step 3: docker-compose.yml 생성**

```yaml
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg17
    container_name: studycafe-postgres
    environment:
      POSTGRES_DB: studycafe
      POSTGRES_USER: studycafe
      POSTGRES_PASSWORD: studycafe
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data

volumes:
  postgres-data:
```

- [ ] **Step 4: Docker 실행 후 연결 확인**

```bash
docker compose up -d
./gradlew bootRun
```

기대 결과: `Started StudyCafeApplication` 로그, 오류 없음

- [ ] **Step 5: 커밋**

```bash
git add build.gradle src/main/resources/application.yaml docker-compose.yml
git commit -m "chore: PostgreSQL로 DB 마이그레이션, WebFlux 추가"
```

---

### Task 2: 글로벌 인프라 구축

**Files:**
- Create: `src/main/java/com/studycafe/global/config/JpaConfig.java`
- Create: `src/main/java/com/studycafe/global/config/SecurityConfig.java`
- Create: `src/main/java/com/studycafe/global/response/ApiResponse.java`
- Create: `src/main/java/com/studycafe/global/exception/ErrorCode.java`
- Create: `src/main/java/com/studycafe/global/exception/CustomException.java`
- Create: `src/main/java/com/studycafe/global/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Produces: `ApiResponse.ok(data)`, `ApiResponse.error(message)`, `CustomException(ErrorCode)`, JPA Auditing 활성화, API 엔드포인트 Security 허용

- [ ] **Step 1: JpaConfig 작성**

```java
// src/main/java/com/studycafe/global/config/JpaConfig.java
package com.studycafe.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
```

- [ ] **Step 2: SecurityConfig 작성 — API 엔드포인트 전체 허용 (인증은 추후 구현)**

```java
// src/main/java/com/studycafe/global/config/SecurityConfig.java
package com.studycafe.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/**").permitAll()
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}
```

- [ ] **Step 3: ApiResponse 작성**

```java
// src/main/java/com/studycafe/global/response/ApiResponse.java
package com.studycafe.global.response;

import lombok.Getter;

@Getter
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;

    private ApiResponse(boolean success, T data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
```

- [ ] **Step 4: ErrorCode 작성**

```java
// src/main/java/com/studycafe/global/exception/ErrorCode.java
package com.studycafe.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다"),
    INSUFFICIENT_POINTS(HttpStatus.BAD_REQUEST, "포인트가 부족합니다"),
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "아이템을 찾을 수 없습니다"),
    ITEM_ALREADY_OWNED(HttpStatus.BAD_REQUEST, "이미 보유한 아이템입니다"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다"),
    PAYMENT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "결제 검증에 실패했습니다"),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "이미 처리된 결제입니다");

    private final HttpStatus httpStatus;
    private final String message;
}
```

- [ ] **Step 5: CustomException 작성**

```java
// src/main/java/com/studycafe/global/exception/CustomException.java
package com.studycafe.global.exception;

import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

- [ ] **Step 6: GlobalExceptionHandler 작성**

```java
// src/main/java/com/studycafe/global/exception/GlobalExceptionHandler.java
package com.studycafe.global.exception;

import com.studycafe.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getMessage()));
    }
}
```

- [ ] **Step 7: 빌드 확인**

```bash
./gradlew compileJava
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/studycafe/global/
git commit -m "feat: 글로벌 인프라 구축 (ApiResponse, ErrorCode, Security)"
```

---

### Task 3: Member 엔티티 구축

**Files:**
- Create: `src/main/java/com/studycafe/domain/member/entity/Member.java`
- Create: `src/main/java/com/studycafe/domain/member/repository/MemberRepository.java`

**Interfaces:**
- Produces: `Member.of(email, nickname)`, `MemberRepository.findById(Long)`

- [ ] **Step 1: Member 엔티티 작성**

```java
// src/main/java/com/studycafe/domain/member/entity/Member.java
package com.studycafe.domain.member.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String nickname;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public static Member of(String email, String nickname) {
        Member member = new Member();
        member.email = email;
        member.nickname = nickname;
        return member;
    }
}
```

- [ ] **Step 2: MemberRepository 작성**

```java
// src/main/java/com/studycafe/domain/member/repository/MemberRepository.java
package com.studycafe.domain.member.repository;

import com.studycafe.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
```

- [ ] **Step 3: 테이블 생성 확인**

```bash
./gradlew bootRun
```

별도 터미널에서:
```bash
docker exec -it studycafe-postgres psql -U studycafe -d studycafe -c "\dt"
```

기대 결과: `members` 테이블 목록에 존재

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/studycafe/domain/member/
git commit -m "feat: Member 엔티티 구축"
```

---

### Task 4: 포인트 도메인 구축

**Files:**
- Create: `src/main/java/com/studycafe/domain/point/entity/PointTransactionType.java`
- Create: `src/main/java/com/studycafe/domain/point/entity/UserPoint.java`
- Create: `src/main/java/com/studycafe/domain/point/entity/PointHistory.java`
- Create: `src/main/java/com/studycafe/domain/point/repository/UserPointRepository.java`
- Create: `src/main/java/com/studycafe/domain/point/repository/PointHistoryRepository.java`
- Create: `src/main/java/com/studycafe/domain/point/service/PointService.java`
- Test: `src/test/java/com/studycafe/domain/point/service/PointServiceTest.java`

**Interfaces:**
- Consumes: `MemberRepository.existsById(Long)` (회원 존재 검증용)
- Produces: `PointService.earn(memberId, amount, type)`, `PointService.spend(memberId, amount, type)`, `PointService.getBalance(memberId): long`

- [ ] **Step 1: PointTransactionType enum 작성**

```java
// src/main/java/com/studycafe/domain/point/entity/PointTransactionType.java
package com.studycafe.domain.point.entity;

public enum PointTransactionType {
    SESSION_REWARD,   // 세션 달성 보상
    STREAK_BONUS,     // 연속 달성 보너스
    PAYMENT_CHARGE,   // 포트원 결제 충전
    STORE_PURCHASE    // 상점 아이템 구매 (차감)
}
```

- [ ] **Step 2: UserPoint 엔티티 작성 — FK만 보유, @Version으로 낙관적 락 적용**

```java
// src/main/java/com/studycafe/domain/point/entity/UserPoint.java
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
    private Long memberId;  // FK만 보유, 연관관계 없음

    @Column(nullable = false)
    private Long balance = 0L;

    // 동시 포인트 수정 시 충돌 감지용 낙관적 락
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

    public void spend(long amount) {
        if (this.balance < amount) {
            throw new CustomException(ErrorCode.INSUFFICIENT_POINTS);
        }
        this.balance -= amount;
    }
}
```

- [ ] **Step 3: PointHistory 엔티티 작성**

```java
// src/main/java/com/studycafe/domain/point/entity/PointHistory.java
package com.studycafe.domain.point.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "point_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;  // FK만 보유

    @Column(nullable = false)
    private Long amount;  // 적립은 양수, 차감은 음수

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointTransactionType type;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public static PointHistory of(Long memberId, long amount, PointTransactionType type) {
        PointHistory history = new PointHistory();
        history.memberId = memberId;
        history.amount = amount;
        history.type = type;
        return history;
    }
}
```

- [ ] **Step 4: MemberRepository에 existsById 확인 (Spring Data 기본 제공)**

`MemberRepository`는 `JpaRepository`를 상속하므로 `existsById(Long)`가 이미 존재한다. 별도 작성 불필요.

- [ ] **Step 5: Repository 두 개 작성**

```java
// src/main/java/com/studycafe/domain/point/repository/UserPointRepository.java
package com.studycafe.domain.point.repository;

import com.studycafe.domain.point.entity.UserPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserPointRepository extends JpaRepository<UserPoint, Long> {
    Optional<UserPoint> findByMemberId(Long memberId);
}
```

```java
// src/main/java/com/studycafe/domain/point/repository/PointHistoryRepository.java
package com.studycafe.domain.point.repository;

import com.studycafe.domain.point.entity.PointHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {
    List<PointHistory> findByMemberIdOrderByCreatedAtDesc(Long memberId);
}
```

- [ ] **Step 6: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/point/service/PointServiceTest.java
package com.studycafe.domain.point.service;

import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.entity.UserPoint;
import com.studycafe.domain.point.repository.PointHistoryRepository;
import com.studycafe.domain.point.repository.UserPointRepository;
import com.studycafe.global.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @InjectMocks
    private PointService pointService;

    @Mock private MemberRepository memberRepository;
    @Mock private UserPointRepository userPointRepository;
    @Mock private PointHistoryRepository pointHistoryRepository;

    private UserPoint userPoint;

    @BeforeEach
    void setUp() {
        userPoint = UserPoint.of(1L);
    }

    @Test
    @DisplayName("포인트 적립 - 잔액이 amount만큼 증가한다")
    void earn_increasesBalance() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(userPointRepository.findByMemberId(1L)).willReturn(Optional.of(userPoint));

        pointService.earn(1L, 100L, PointTransactionType.SESSION_REWARD);

        assertThat(userPoint.getBalance()).isEqualTo(100L);
        verify(pointHistoryRepository).save(any());
    }

    @Test
    @DisplayName("포인트 차감 - 잔액 부족 시 CustomException 발생")
    void spend_throwsWhenInsufficient() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(userPointRepository.findByMemberId(1L)).willReturn(Optional.of(userPoint));

        assertThatThrownBy(() -> pointService.spend(1L, 100L, PointTransactionType.STORE_PURCHASE))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("잔액 조회 - UserPoint가 없으면 0을 반환한다")
    void getBalance_returnsZeroWhenNotExists() {
        given(userPointRepository.findByMemberId(1L)).willReturn(Optional.empty());

        long balance = pointService.getBalance(1L);

        assertThat(balance).isEqualTo(0L);
    }
}
```

- [ ] **Step 7: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.point.service.PointServiceTest"
```

기대 결과: FAIL — `PointService` 클래스 없음

- [ ] **Step 8: PointService 구현**

```java
// src/main/java/com/studycafe/domain/point/service/PointService.java
package com.studycafe.domain.point.service;

import com.studycafe.domain.member.repository.MemberRepository;
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

@Service
@RequiredArgsConstructor
public class PointService {

    private final MemberRepository memberRepository;
    private final UserPointRepository userPointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    @Transactional
    public void earn(Long memberId, long amount, PointTransactionType type) {
        validateMember(memberId);
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseGet(() -> userPointRepository.save(UserPoint.of(memberId)));

        userPoint.earn(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, amount, type));
    }

    @Transactional
    public void spend(Long memberId, long amount, PointTransactionType type) {
        validateMember(memberId);
        UserPoint userPoint = userPointRepository.findByMemberId(memberId)
                .orElseGet(() -> userPointRepository.save(UserPoint.of(memberId)));

        userPoint.spend(amount);
        pointHistoryRepository.save(PointHistory.of(memberId, -amount, type));
    }

    @Transactional(readOnly = true)
    public long getBalance(Long memberId) {
        return userPointRepository.findByMemberId(memberId)
                .map(UserPoint::getBalance)
                .orElse(0L);
    }

    private void validateMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }
    }
}
```

- [ ] **Step 9: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.point.service.PointServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/studycafe/domain/point/ src/test/java/com/studycafe/domain/point/
git commit -m "feat: 포인트 도메인 구축 (FK 구조, 낙관적 락, PointService)"
```

---

### Task 5: 상점 도메인 구축

**Files:**
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
- Consumes: `PointService.spend(memberId, amount, type)`, `MemberRepository.existsById(Long)`
- Produces: `StoreService.getItems(): List<StoreItemResponse>`, `StoreService.getMyItems(memberId): List<StoreItemResponse>`, `StoreService.purchase(memberId, itemId)`

- [ ] **Step 1: ItemCategory enum 작성**

```java
// src/main/java/com/studycafe/domain/store/entity/ItemCategory.java
package com.studycafe.domain.store.entity;

public enum ItemCategory {
    BACKGROUND,  // 카페 배경 테마
    SOUND,       // 앰비언트 사운드 팩
    NPC          // NPC 캐릭터 팩
}
```

- [ ] **Step 2: StoreItem 엔티티 작성**

```java
// src/main/java/com/studycafe/domain/store/entity/StoreItem.java
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

- [ ] **Step 3: UserItem 엔티티 작성 — FK만 보유**

```java
// src/main/java/com/studycafe/domain/store/entity/UserItem.java
package com.studycafe.domain.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "store_item_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class UserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;      // FK만 보유

    @Column(name = "store_item_id", nullable = false)
    private Long storeItemId;   // FK만 보유

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime purchasedAt;

    public static UserItem of(Long memberId, Long storeItemId) {
        UserItem userItem = new UserItem();
        userItem.memberId = memberId;
        userItem.storeItemId = storeItemId;
        return userItem;
    }
}
```

- [ ] **Step 4: Repository 두 개 작성**

```java
// src/main/java/com/studycafe/domain/store/repository/StoreItemRepository.java
package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.StoreItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreItemRepository extends JpaRepository<StoreItem, Long> {
}
```

```java
// src/main/java/com/studycafe/domain/store/repository/UserItemRepository.java
package com.studycafe.domain.store.repository;

import com.studycafe.domain.store.entity.UserItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserItemRepository extends JpaRepository<UserItem, Long> {
    boolean existsByMemberIdAndStoreItemId(Long memberId, Long storeItemId);
    List<UserItem> findByMemberId(Long memberId);
}
```

- [ ] **Step 5: StoreItemResponse DTO 작성**

```java
// src/main/java/com/studycafe/domain/store/dto/StoreItemResponse.java
package com.studycafe.domain.store.dto;

import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;
import lombok.Getter;

@Getter
public class StoreItemResponse {
    private final Long id;
    private final String name;
    private final String description;
    private final ItemCategory category;
    private final Long price;
    private final String resourcePath;

    private StoreItemResponse(StoreItem item) {
        this.id = item.getId();
        this.name = item.getName();
        this.description = item.getDescription();
        this.category = item.getCategory();
        this.price = item.getPrice();
        this.resourcePath = item.getResourcePath();
    }

    public static StoreItemResponse from(StoreItem item) {
        return new StoreItemResponse(item);
    }
}
```

- [ ] **Step 6: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/store/service/StoreServiceTest.java
package com.studycafe.domain.store.service;

import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.domain.store.entity.ItemCategory;
import com.studycafe.domain.store.entity.StoreItem;
import com.studycafe.domain.store.repository.StoreItemRepository;
import com.studycafe.domain.store.repository.UserItemRepository;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @InjectMocks
    private StoreService storeService;

    @Mock private MemberRepository memberRepository;
    @Mock private StoreItemRepository storeItemRepository;
    @Mock private UserItemRepository userItemRepository;
    @Mock private PointService pointService;

    private StoreItem item;

    @BeforeEach
    void setUp() {
        item = StoreItem.of("빈티지 카페", "따뜻한 빈티지 분위기", ItemCategory.BACKGROUND, 500L, "/bg/vintage.png");
    }

    @Test
    @DisplayName("아이템 구매 - 포인트 차감 후 UserItem 저장")
    void purchase_deductsPointAndSavesItem() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(storeItemRepository.findById(1L)).willReturn(Optional.of(item));
        given(userItemRepository.existsByMemberIdAndStoreItemId(1L, 1L)).willReturn(false);

        storeService.purchase(1L, 1L);

        verify(pointService).spend(1L, 500L, PointTransactionType.STORE_PURCHASE);
        verify(userItemRepository).save(any());
    }

    @Test
    @DisplayName("아이템 구매 - 이미 보유한 아이템이면 예외 발생")
    void purchase_throwsWhenAlreadyOwned() {
        given(memberRepository.existsById(1L)).willReturn(true);
        given(storeItemRepository.findById(1L)).willReturn(Optional.of(item));
        given(userItemRepository.existsByMemberIdAndStoreItemId(1L, 1L)).willReturn(true);

        assertThatThrownBy(() -> storeService.purchase(1L, 1L))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.ITEM_ALREADY_OWNED.getMessage());
    }
}
```

- [ ] **Step 7: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.store.service.StoreServiceTest"
```

기대 결과: FAIL — `StoreService` 클래스 없음

- [ ] **Step 8: StoreService 구현**

```java
// src/main/java/com/studycafe/domain/store/service/StoreService.java
package com.studycafe.domain.store.service;

import com.studycafe.domain.member.repository.MemberRepository;
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

    private final MemberRepository memberRepository;
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
        // 1) 내가 보유한 storeItemId 목록 조회
        List<Long> storeItemIds = userItemRepository.findByMemberId(memberId).stream()
                .map(UserItem::getStoreItemId)
                .toList();
        // 2) storeItemId로 배치 조회 — 쿼리 1번으로 N+1 방지
        return storeItemRepository.findAllById(storeItemIds).stream()
                .map(StoreItemResponse::from)
                .toList();
    }

    @Transactional
    public void purchase(Long memberId, Long itemId) {
        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }
        StoreItem item = storeItemRepository.findById(itemId)
                .orElseThrow(() -> new CustomException(ErrorCode.ITEM_NOT_FOUND));

        if (userItemRepository.existsByMemberIdAndStoreItemId(memberId, itemId)) {
            throw new CustomException(ErrorCode.ITEM_ALREADY_OWNED);
        }

        pointService.spend(memberId, item.getPrice(), PointTransactionType.STORE_PURCHASE);
        userItemRepository.save(UserItem.of(memberId, itemId));
    }
}
```

- [ ] **Step 9: StoreController 구현**

```java
// src/main/java/com/studycafe/domain/store/controller/StoreController.java
package com.studycafe.domain.store.controller;

import com.studycafe.domain.store.dto.StoreItemResponse;
import com.studycafe.domain.store.service.StoreService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/items")
    public ApiResponse<List<StoreItemResponse>> getItems() {
        return ApiResponse.ok(storeService.getItems());
    }

    @GetMapping("/my-items")
    public ApiResponse<List<StoreItemResponse>> getMyItems(@RequestParam Long memberId) {
        return ApiResponse.ok(storeService.getMyItems(memberId));
    }

    @PostMapping("/items/{itemId}/purchase")
    public ApiResponse<Void> purchase(@RequestParam Long memberId, @PathVariable Long itemId) {
        storeService.purchase(memberId, itemId);
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 10: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.store.service.StoreServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/studycafe/domain/store/ src/test/java/com/studycafe/domain/store/
git commit -m "feat: 상점 도메인 구축 (FK 구조, findAllById 배치 조회로 N+1 방지)"
```

---

### Task 6: 포트원 V2 결제 연동

**Files:**
- Create: `src/main/java/com/studycafe/infra/portone/PortOneProperties.java`
- Create: `src/main/java/com/studycafe/infra/portone/dto/PortOnePaymentResponse.java`
- Create: `src/main/java/com/studycafe/infra/portone/PortOneClient.java`
- Create: `src/main/java/com/studycafe/domain/payment/entity/PaymentStatus.java`
- Create: `src/main/java/com/studycafe/domain/payment/entity/Payment.java`
- Create: `src/main/java/com/studycafe/domain/payment/repository/PaymentRepository.java`
- Create: `src/main/java/com/studycafe/domain/payment/service/PaymentService.java`
- Create: `src/main/java/com/studycafe/domain/payment/controller/PaymentController.java`
- Test: `src/test/java/com/studycafe/domain/payment/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `PointService.earn(memberId, amount, type)`, `MemberRepository.existsById(Long)`
- Produces:
  - `PaymentService.initiate(memberId, amount): String` — 포트원 paymentId 생성 후 반환
  - `PaymentService.confirm(portonePaymentId)` — 웹훅 수신 후 검증 및 포인트 적립
  - `POST /api/v1/payments/webhook` — 포트원 웹훅 수신 엔드포인트

- [ ] **Step 1: PortOneProperties 작성**

```java
// src/main/java/com/studycafe/infra/portone/PortOneProperties.java
package com.studycafe.infra.portone;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {
    private String apiSecret;
    private String baseUrl = "https://api.portone.io";
}
```

- [ ] **Step 2: PortOnePaymentResponse DTO 작성**

```java
// src/main/java/com/studycafe/infra/portone/dto/PortOnePaymentResponse.java
package com.studycafe.infra.portone.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 포트원 V2 GET /payments/{paymentId} 응답에서 필요한 필드만 매핑
@Getter
@NoArgsConstructor
public class PortOnePaymentResponse {
    private String id;
    private String status;

    @JsonProperty("amount")
    private AmountDetail amount;

    @Getter
    @NoArgsConstructor
    public static class AmountDetail {
        private long total;
    }

    public boolean isPaid() {
        return "PAID".equals(this.status);
    }

    public long getTotalAmount() {
        return amount != null ? amount.getTotal() : 0L;
    }

    // 테스트용 팩토리 메서드
    public static PortOnePaymentResponse paid(long totalAmount) {
        PortOnePaymentResponse response = new PortOnePaymentResponse();
        response.status = "PAID";
        response.amount = new AmountDetail();
        response.amount.total = totalAmount;
        return response;
    }

    public static PortOnePaymentResponse failed() {
        PortOnePaymentResponse response = new PortOnePaymentResponse();
        response.status = "FAILED";
        return response;
    }
}
```

- [ ] **Step 3: PortOneClient 작성**

```java
// src/main/java/com/studycafe/infra/portone/PortOneClient.java
package com.studycafe.infra.portone;

import com.studycafe.infra.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class PortOneClient {

    private final PortOneProperties properties;
    private final WebClient webClient = WebClient.create();

    public PortOnePaymentResponse verifyPayment(String paymentId) {
        return webClient.get()
                .uri(properties.getBaseUrl() + "/payments/{paymentId}", paymentId)
                .header("Authorization", "PortOne " + properties.getApiSecret())
                .retrieve()
                .bodyToMono(PortOnePaymentResponse.class)
                .block();
    }
}
```

- [ ] **Step 4: PaymentStatus enum 작성**

```java
// src/main/java/com/studycafe/domain/payment/entity/PaymentStatus.java
package com.studycafe.domain.payment.entity;

public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED
}
```

- [ ] **Step 5: Payment 엔티티 작성 — FK만 보유**

```java
// src/main/java/com/studycafe/domain/payment/entity/Payment.java
package com.studycafe.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String portonePaymentId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;  // FK만 보유

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public static Payment pending(String portonePaymentId, Long memberId, long amount) {
        Payment payment = new Payment();
        payment.portonePaymentId = portonePaymentId;
        payment.memberId = memberId;
        payment.amount = amount;
        payment.status = PaymentStatus.PENDING;
        return payment;
    }

    public void markPaid() { this.status = PaymentStatus.PAID; }
    public void markFailed() { this.status = PaymentStatus.FAILED; }
}
```

- [ ] **Step 6: PaymentRepository 작성**

```java
// src/main/java/com/studycafe/domain/payment/repository/PaymentRepository.java
package com.studycafe.domain.payment.repository;

import com.studycafe.domain.payment.entity.Payment;
import com.studycafe.domain.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPortonePaymentId(String portonePaymentId);
    boolean existsByPortonePaymentIdAndStatus(String portonePaymentId, PaymentStatus status);
}
```

- [ ] **Step 7: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/payment/service/PaymentServiceTest.java
package com.studycafe.domain.payment.service;

import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.payment.entity.Payment;
import com.studycafe.domain.payment.entity.PaymentStatus;
import com.studycafe.domain.payment.repository.PaymentRepository;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.infra.portone.PortOneClient;
import com.studycafe.infra.portone.dto.PortOnePaymentResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Mock private MemberRepository memberRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PortOneClient portOneClient;
    @Mock private PointService pointService;

    @Test
    @DisplayName("결제 확인 - 포트원 검증 성공 시 포인트 적립")
    void confirm_earnsPointsOnSuccess() {
        Payment payment = Payment.pending("pay-123", 1L, 10000L);
        given(paymentRepository.existsByPortonePaymentIdAndStatus("pay-123", PaymentStatus.PAID)).willReturn(false);
        given(paymentRepository.findByPortonePaymentId("pay-123")).willReturn(Optional.of(payment));
        given(portOneClient.verifyPayment("pay-123")).willReturn(PortOnePaymentResponse.paid(10000L));

        paymentService.confirm("pay-123");

        verify(pointService).earn(1L, 10000L, PointTransactionType.PAYMENT_CHARGE);
    }

    @Test
    @DisplayName("결제 확인 - 금액 불일치 시 FAILED 처리")
    void confirm_failsOnAmountMismatch() {
        Payment payment = Payment.pending("pay-123", 1L, 10000L);
        given(paymentRepository.existsByPortonePaymentIdAndStatus("pay-123", PaymentStatus.PAID)).willReturn(false);
        given(paymentRepository.findByPortonePaymentId("pay-123")).willReturn(Optional.of(payment));
        given(portOneClient.verifyPayment("pay-123")).willReturn(PortOnePaymentResponse.paid(5000L));

        assertThatThrownBy(() -> paymentService.confirm("pay-123"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.PAYMENT_VERIFICATION_FAILED.getMessage());
    }

    @Test
    @DisplayName("중복 웹훅 - 이미 PAID 처리된 결제는 예외 발생")
    void confirm_throwsOnDuplicateWebhook() {
        given(paymentRepository.existsByPortonePaymentIdAndStatus("pay-123", PaymentStatus.PAID)).willReturn(true);

        assertThatThrownBy(() -> paymentService.confirm("pay-123"))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.DUPLICATE_PAYMENT.getMessage());
    }
}
```

- [ ] **Step 8: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.payment.service.PaymentServiceTest"
```

기대 결과: FAIL — `PaymentService` 클래스 없음

- [ ] **Step 9: PaymentService 구현**

```java
// src/main/java/com/studycafe/domain/payment/service/PaymentService.java
package com.studycafe.domain.payment.service;

import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.domain.payment.entity.Payment;
import com.studycafe.domain.payment.entity.PaymentStatus;
import com.studycafe.domain.payment.repository.PaymentRepository;
import com.studycafe.domain.point.entity.PointTransactionType;
import com.studycafe.domain.point.service.PointService;
import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;
import com.studycafe.infra.portone.PortOneClient;
import com.studycafe.infra.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    private final PointService pointService;

    @Transactional
    public String initiate(Long memberId, long amount) {
        if (!memberRepository.existsById(memberId)) {
            throw new CustomException(ErrorCode.MEMBER_NOT_FOUND);
        }
        String portonePaymentId = "studycafe-" + UUID.randomUUID();
        paymentRepository.save(Payment.pending(portonePaymentId, memberId, amount));
        return portonePaymentId;
    }

    @Transactional
    public void confirm(String portonePaymentId) {
        if (paymentRepository.existsByPortonePaymentIdAndStatus(portonePaymentId, PaymentStatus.PAID)) {
            throw new CustomException(ErrorCode.DUPLICATE_PAYMENT);
        }

        Payment payment = paymentRepository.findByPortonePaymentId(portonePaymentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));

        PortOnePaymentResponse portOneResponse = portOneClient.verifyPayment(portonePaymentId);

        if (!portOneResponse.isPaid() || portOneResponse.getTotalAmount() != payment.getAmount()) {
            payment.markFailed();
            throw new CustomException(ErrorCode.PAYMENT_VERIFICATION_FAILED);
        }

        payment.markPaid();
        pointService.earn(payment.getMemberId(), payment.getAmount(), PointTransactionType.PAYMENT_CHARGE);
    }
}
```

- [ ] **Step 10: PaymentController 구현**

```java
// src/main/java/com/studycafe/domain/payment/controller/PaymentController.java
package com.studycafe.domain.payment.controller;

import com.studycafe.domain.payment.service.PaymentService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ApiResponse<Map<String, String>> initiate(@RequestParam Long memberId,
                                                      @RequestParam Long amount) {
        String paymentId = paymentService.initiate(memberId, amount);
        return ApiResponse.ok(Map.of("paymentId", paymentId));
    }

    // 포트원 콘솔에서 이 URL을 웹훅 주소로 등록
    @PostMapping("/webhook")
    public ApiResponse<Void> webhook(@RequestBody Map<String, Object> payload) {
        String paymentId = (String) payload.get("paymentId");
        paymentService.confirm(paymentId);
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 11: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.payment.service.PaymentServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 12: 전체 테스트 확인**

```bash
./gradlew test
```

기대 결과: 전체 테스트 통과

- [ ] **Step 13: 커밋**

```bash
git add src/main/java/com/studycafe/domain/payment/ src/main/java/com/studycafe/infra/portone/ src/test/java/com/studycafe/domain/payment/
git commit -m "feat: 포트원 V2 결제 연동 및 포인트 충전 구현 (FK 구조, 멱등성, 금액 검증)"
```
