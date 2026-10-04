# 카페 좌석 실시간 점유 — 백엔드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인한 회원이 STOMP로 10개 좌석 중 하나에 앉고, 좌석이 바뀔 때마다 모든 접속자에게 전체 좌석표가 실시간으로 전달된다.

**Architecture:** 좌석은 서버 메모리의 `SeatRegistry`(동기화된 배열 10칸)에만 둔다. `CafeSeatService`가 닉네임 조회와 브로드캐스트를, `SeatPresenceListener`가 회원별 STOMP 세션 집계와 30초 유예를 맡는다. STOMP CONNECT 프레임의 JWT는 `StompAuthChannelInterceptor`가 검증한다.

**Tech Stack:** Spring Boot 4.1, Spring WebSocket(STOMP, 내장 simple broker), Jackson 3, JUnit 5, Mockito, Testcontainers PostgreSQL

**Spec:** `docs/superpowers/specs/2026-10-04-cafe-seats-design.md`

## Global Constraints

- 좌석 수 10, 좌석 ID `0`~`9`
- 연결 종료 후 자리 유지 시간 30초 (`cafe.seat.grace-period: 30s`)
- STOMP 엔드포인트 `/ws`, 앱 prefix `/app`, 브로커 `/topic`·`/queue`, 사용자 prefix `/user`
- 메시지 주소: 구독 `/app/cafe/seats`(1회 응답), `/topic/cafe/seats`(변경마다), 보내기 `/app/cafe/seat.take` `{ "seatId": n }`, `/app/cafe/status` `{ "studying": bool }`, 개인 오류 `/user/queue/errors` `{ "code": "SEAT_TAKEN" | "INVALID_SEAT" }`
- 좌석 전체 형식: `[{ "seatId": 0, "occupant": { "memberId", "nickname", "sittingSince"(ISO-8601), "studying" } | null }]`, 항상 10개
- 하트비트 10초/10초
- 패키지 `com.studycafe.domain.cafe`, 주석은 한글, 테스트 이름은 기존처럼 한글 메서드명
- 테스트는 `./gradlew test`로 실행 (Docker 필요: Testcontainers)

## Review Focus

- 같은 STOMP 세션의 `SessionDisconnectEvent`가 두 번 와도 연결 수가 한 번만 줄어야 한다 (Spring 문서상 중복 발생 가능) → Task 3 테스트
- 유예 타이머가 이미 실행 대기 중일 때 재연결하면 자리가 비워지지 않아야 한다 (cancel이 늦은 경우) → Task 3 테스트
- 두 회원이 같은 빈자리를 동시에 고르면 정확히 한 명만 앉아야 한다 → Task 1 테스트
- 자리를 옮겨도 `sittingSince`와 `studying`이 유지되어야 한다 (정보 카드의 "앉은 지 얼마나") → Task 1 테스트
- 토큰 없이·위조 토큰으로 CONNECT하면 연결이 거부되어야 한다 → Task 4 테스트

---

## 파일 구조

| 파일 | 역할 |
|---|---|
| `build.gradle` (수정) | `spring-boot-starter-websocket` 추가 |
| `src/main/resources/application.yaml` (수정) | `cafe.seat.grace-period` |
| `global/exception/ErrorCode.java` (수정) | `INVALID_SEAT`, `SEAT_TAKEN` |
| `domain/cafe/exception/CafeException.java` | 좌석 도메인 예외 |
| `domain/cafe/dto/Occupant.java`, `SeatView.java` | 좌석표 응답 |
| `domain/cafe/dto/TakeSeatRequest.java`, `StatusRequest.java`, `SeatError.java` | STOMP 요청·오류 |
| `domain/cafe/service/SeatRegistry.java` | 좌석 상태와 규칙 (메모리) |
| `domain/cafe/service/CafeSeatService.java` | 닉네임 조회 + 브로드캐스트 |
| `domain/cafe/service/SeatPresenceListener.java` | 회원별 연결 집계 + 30초 유예 |
| `domain/cafe/controller/CafeSeatController.java` | STOMP 메시지 매핑 |
| `infra/jwt/StompAuthChannelInterceptor.java` | CONNECT JWT 검증 |
| `global/config/WebSocketConfig.java` | STOMP 설정, 좌석용 스케줄러 |
| `global/config/SecurityConfig.java` (수정) | `/ws` 핸드셰이크 허용 |

---

### Task 1: 좌석 저장소 (`SeatRegistry`)

**Files:**
- Modify: `src/main/java/com/studycafe/global/exception/ErrorCode.java`
- Create: `src/main/java/com/studycafe/domain/cafe/exception/CafeException.java`
- Create: `src/main/java/com/studycafe/domain/cafe/dto/Occupant.java`
- Create: `src/main/java/com/studycafe/domain/cafe/dto/SeatView.java`
- Create: `src/main/java/com/studycafe/domain/cafe/service/SeatRegistry.java`
- Test: `src/test/java/com/studycafe/domain/cafe/service/SeatRegistryTest.java`

**Interfaces:**
- Produces:
  - `record Occupant(Long memberId, String nickname, Instant sittingSince, boolean studying)`
  - `record SeatView(int seatId, Occupant occupant)` — 빈자리면 `occupant == null`
  - `SeatRegistry.SEAT_COUNT = 10`
  - `List<SeatView> SeatRegistry.snapshot()`
  - `boolean SeatRegistry.take(Long memberId, String nickname, Integer seatId, Instant now)` — 바뀌었으면 true, 이미 그 자리면 false. 범위 밖·null이면 `CafeException(INVALID_SEAT)`, 다른 회원 자리면 `CafeException(SEAT_TAKEN)`
  - `boolean SeatRegistry.updateStudying(Long memberId, boolean studying)` — 앉아 있지 않거나 값이 같으면 false
  - `boolean SeatRegistry.leave(Long memberId)` — 비운 자리가 있으면 true

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatRegistryTest {

    private final SeatRegistry registry = new SeatRegistry();
    private final Instant t0 = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void 처음에는_10개_좌석이_모두_비어있다() {
        assertThat(registry.snapshot()).hasSize(10)
                .allSatisfy(seat -> assertThat(seat.occupant()).isNull());
        assertThat(registry.snapshot()).extracting(SeatView::seatId)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void 빈자리에_앉으면_좌석표에_표시된다() {
        assertThat(registry.take(1L, "라떼", 3, t0)).isTrue();

        SeatView seat = registry.snapshot().get(3);
        assertThat(seat.occupant().memberId()).isEqualTo(1L);
        assertThat(seat.occupant().nickname()).isEqualTo("라떼");
        assertThat(seat.occupant().sittingSince()).isEqualTo(t0);
        assertThat(seat.occupant().studying()).isFalse();
    }

    @Test
    void 다른_회원이_앉은_자리는_SEAT_TAKEN() {
        registry.take(1L, "라떼", 3, t0);

        assertThatThrownBy(() -> registry.take(2L, "모카", 3, t0))
                .isInstanceOf(CafeException.class)
                .hasMessage(ErrorCode.SEAT_TAKEN.getMessage());
    }

    @Test
    void 범위_밖이거나_없는_좌석은_INVALID_SEAT() {
        for (Integer seatId : new Integer[]{-1, 10, null}) {
            assertThatThrownBy(() -> registry.take(1L, "라떼", seatId, t0))
                    .isInstanceOf(CafeException.class)
                    .hasMessage(ErrorCode.INVALID_SEAT.getMessage());
        }
    }

    @Test
    void 이미_앉은_자리를_다시_고르면_바뀌지_않는다() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.take(1L, "라떼", 3, t0.plusSeconds(60))).isFalse();
        assertThat(registry.snapshot().get(3).occupant().sittingSince()).isEqualTo(t0);
    }

    @Test
    void 자리를_옮기면_이전_자리는_비고_앉은_시각과_공부상태는_유지된다() {
        registry.take(1L, "라떼", 3, t0);
        registry.updateStudying(1L, true);

        assertThat(registry.take(1L, "라떼", 5, t0.plusSeconds(600))).isTrue();

        assertThat(registry.snapshot().get(3).occupant()).isNull();
        assertThat(registry.snapshot().get(5).occupant().sittingSince()).isEqualTo(t0);
        assertThat(registry.snapshot().get(5).occupant().studying()).isTrue();
    }

    @Test
    void 공부상태가_바뀔_때만_true() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.updateStudying(1L, true)).isTrue();
        assertThat(registry.updateStudying(1L, true)).isFalse();
        assertThat(registry.snapshot().get(3).occupant().studying()).isTrue();
    }

    @Test
    void 앉지_않은_회원의_공부상태는_무시한다() {
        assertThat(registry.updateStudying(9L, true)).isFalse();
    }

    @Test
    void 일어나면_자리가_빈다() {
        registry.take(1L, "라떼", 3, t0);

        assertThat(registry.leave(1L)).isTrue();
        assertThat(registry.leave(1L)).isFalse();
        assertThat(registry.snapshot().get(3).occupant()).isNull();
    }

    @Test
    void 같은_빈자리를_동시에_고르면_한_명만_앉는다() throws InterruptedException {
        int members = 20;
        ExecutorService pool = Executors.newFixedThreadPool(members);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger seated = new AtomicInteger();
        for (long id = 1; id <= members; id++) {
            long memberId = id;
            pool.submit(() -> {
                start.await();
                try {
                    if (registry.take(memberId, "손님" + memberId, 0, t0)) seated.incrementAndGet();
                } catch (CafeException ignored) {
                    // 자리를 놓친 회원
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(seated.get()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.SeatRegistryTest"`
Expected: 컴파일 실패 (`SeatRegistry`, `CafeException` 등이 없음)

- [ ] **Step 3: 구현**

`ErrorCode.java` — 마지막 항목 `ITEM_ALREADY_OWNED(...)`의 `;`를 `,`로 바꾸고 아래를 덧붙인다.

```java
    ITEM_ALREADY_OWNED(HttpStatus.CONFLICT, "이미 보유한 아이템입니다"),

    // 카페 좌석
    INVALID_SEAT(HttpStatus.BAD_REQUEST, "존재하지 않는 자리입니다"),
    SEAT_TAKEN(HttpStatus.CONFLICT, "이미 다른 분이 앉은 자리입니다");
```

`CafeException.java`

```java
package com.studycafe.domain.cafe.exception;

import com.studycafe.global.exception.CustomException;
import com.studycafe.global.exception.ErrorCode;

public class CafeException extends CustomException {

    public CafeException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

`Occupant.java`

```java
package com.studycafe.domain.cafe.dto;

import java.time.Instant;

// 좌석에 앉은 회원
public record Occupant(Long memberId, String nickname, Instant sittingSince, boolean studying) {

    public Occupant withStudying(boolean studying) {
        return new Occupant(memberId, nickname, sittingSince, studying);
    }
}
```

`SeatView.java`

```java
package com.studycafe.domain.cafe.dto;

// 좌석 하나. 빈자리면 occupant가 null
public record SeatView(int seatId, Occupant occupant) {
}
```

`SeatRegistry.java`

```java
package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.Occupant;
import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 카페 좌석표 — 서버 메모리에만 둔다 (서버 1대 전제).
// ponytail: 서버를 여러 대로 늘리면 이 클래스의 저장소를 Redis로 바꾼다 (설계 문서 6절)
@Component
public class SeatRegistry {

    public static final int SEAT_COUNT = 10;

    private final Occupant[] seats = new Occupant[SEAT_COUNT];

    public synchronized List<SeatView> snapshot() {
        List<SeatView> views = new ArrayList<>(SEAT_COUNT);
        for (int i = 0; i < SEAT_COUNT; i++) {
            views.add(new SeatView(i, seats[i]));
        }
        return views;
    }

    // 앉기 또는 자리 이동. 이동해도 앉은 시각과 공부 상태는 유지한다
    public synchronized boolean take(Long memberId, String nickname, Integer seatId, Instant now) {
        if (seatId == null || seatId < 0 || seatId >= SEAT_COUNT) {
            throw new CafeException(ErrorCode.INVALID_SEAT);
        }
        Occupant target = seats[seatId];
        if (target != null) {
            if (target.memberId().equals(memberId)) return false;
            throw new CafeException(ErrorCode.SEAT_TAKEN);
        }
        int current = indexOf(memberId);
        if (current >= 0) {
            seats[seatId] = seats[current];
            seats[current] = null;
        } else {
            seats[seatId] = new Occupant(memberId, nickname, now, false);
        }
        return true;
    }

    public synchronized boolean updateStudying(Long memberId, boolean studying) {
        int current = indexOf(memberId);
        if (current < 0 || seats[current].studying() == studying) return false;
        seats[current] = seats[current].withStudying(studying);
        return true;
    }

    public synchronized boolean leave(Long memberId) {
        int current = indexOf(memberId);
        if (current < 0) return false;
        seats[current] = null;
        return true;
    }

    private int indexOf(Long memberId) {
        for (int i = 0; i < SEAT_COUNT; i++) {
            if (seats[i] != null && seats[i].memberId().equals(memberId)) return i;
        }
        return -1;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.SeatRegistryTest"`
Expected: PASS (10개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/studycafe/global/exception/ErrorCode.java src/main/java/com/studycafe/domain/cafe src/test/java/com/studycafe/domain/cafe
git commit -m "feat: 카페 좌석 저장소(SeatRegistry) 추가"
```

---

### Task 2: 좌석 서비스 (`CafeSeatService`) + WebSocket 의존성

**Files:**
- Modify: `build.gradle` (dependencies 블록)
- Create: `src/main/java/com/studycafe/domain/cafe/service/CafeSeatService.java`
- Test: `src/test/java/com/studycafe/domain/cafe/service/CafeSeatServiceTest.java`

**Interfaces:**
- Consumes: `SeatRegistry` (Task 1), `MemberRepository.findById(Long)`, `MemberException(ErrorCode)`
- Produces:
  - `CafeSeatService.TOPIC = "/topic/cafe/seats"`
  - `List<SeatView> snapshot()`
  - `void take(Long memberId, Integer seatId)` — 바뀌면 브로드캐스트
  - `void updateStudying(Long memberId, boolean studying)` — 바뀌면 브로드캐스트
  - `void leave(Long memberId)` — 비웠으면 브로드캐스트

- [ ] **Step 1: 의존성 추가**

`build.gradle`의 `spring-boot-starter-validation` 줄 아래에 추가:

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.exception.CafeException;
import com.studycafe.domain.member.entity.Member;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CafeSeatServiceTest {

    private final SeatRegistry registry = new SeatRegistry();
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final CafeSeatService service = new CafeSeatService(registry, memberRepository, messagingTemplate);

    @BeforeEach
    void setUp() {
        Member member = mock(Member.class);
        when(member.getNickname()).thenReturn("라떼");
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
    }

    @Test
    void 앉으면_닉네임과_함께_좌석표를_브로드캐스트한다() {
        service.take(1L, 3);

        assertThat(registry.snapshot().get(3).occupant().nickname()).isEqualTo("라떼");
        verify(messagingTemplate).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 없는_회원은_앉을_수_없다() {
        when(memberRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.take(9L, 3)).isInstanceOf(MemberException.class);
        verify(messagingTemplate, never()).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 이미_찬_자리는_예외이고_브로드캐스트하지_않는다() {
        registry.take(2L, "모카", 3, java.time.Instant.now());

        assertThatThrownBy(() -> service.take(1L, 3)).isInstanceOf(CafeException.class);
        verify(messagingTemplate, never()).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }

    @Test
    void 공부상태와_일어나기는_바뀐_경우에만_브로드캐스트한다() {
        service.take(1L, 3);
        service.updateStudying(1L, true);
        service.updateStudying(1L, true);
        service.leave(1L);
        service.leave(1L);

        // take 1 + studying 1 + leave 1
        verify(messagingTemplate, times(3)).convertAndSend(eq(CafeSeatService.TOPIC), any(Object.class));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.CafeSeatServiceTest"`
Expected: 컴파일 실패 (`CafeSeatService` 없음)

- [ ] **Step 4: 구현**

```java
package com.studycafe.domain.cafe.service;

import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.member.exception.MemberException;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

// 좌석 규칙(SeatRegistry)에 닉네임 조회와 브로드캐스트를 붙인다
@Service
@RequiredArgsConstructor
public class CafeSeatService {

    public static final String TOPIC = "/topic/cafe/seats";

    private final SeatRegistry seatRegistry;
    private final MemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public List<SeatView> snapshot() {
        return seatRegistry.snapshot();
    }

    public void take(Long memberId, Integer seatId) {
        String nickname = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND))
                .getNickname();
        if (seatRegistry.take(memberId, nickname, seatId, Instant.now())) broadcast();
    }

    public void updateStudying(Long memberId, boolean studying) {
        if (seatRegistry.updateStudying(memberId, studying)) broadcast();
    }

    public void leave(Long memberId) {
        if (seatRegistry.leave(memberId)) broadcast();
    }

    // 변경분이 아니라 항상 좌석 10개 전체를 보낸다
    private void broadcast() {
        messagingTemplate.convertAndSend(TOPIC, seatRegistry.snapshot());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.CafeSeatServiceTest"`
Expected: PASS (4개)

- [ ] **Step 6: 커밋**

```bash
git add build.gradle src/main/java/com/studycafe/domain/cafe/service/CafeSeatService.java src/test/java/com/studycafe/domain/cafe/service/CafeSeatServiceTest.java
git commit -m "feat: 좌석 서비스와 WebSocket 의존성 추가"
```

---

### Task 3: 연결 집계와 30초 유예 (`SeatPresenceListener`)

**Files:**
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/studycafe/domain/cafe/service/SeatPresenceListener.java`
- Test: `src/test/java/com/studycafe/domain/cafe/service/SeatPresenceListenerTest.java`

**Interfaces:**
- Consumes: `CafeSeatService.leave(Long)` (Task 2), 스케줄러 빈 이름 `seatTaskScheduler` (Task 4에서 정의 — 이 Task의 단위 테스트는 목으로 대체)
- Produces:
  - `void connected(Long memberId, String sessionId)`
  - `void disconnected(Long memberId, String sessionId)`
  - `@EventListener onConnected(SessionConnectedEvent)`, `onDisconnected(SessionDisconnectEvent)`

- [ ] **Step 1: 설정값 추가**

`application.yaml` 맨 아래에 추가:

```yaml
cafe:
  seat:
    grace-period: 30s                   # 연결이 끊긴 뒤 자리를 잡아두는 시간
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package com.studycafe.domain.cafe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatPresenceListenerTest {

    private final CafeSeatService cafeSeatService = mock(CafeSeatService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final SeatPresenceListener listener =
            new SeatPresenceListener(cafeSeatService, scheduler, Duration.ofSeconds(30));

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        return task.getValue();
    }

    @Test
    void 마지막_연결이_끊기면_유예_후_자리를_비운다() {
        listener.connected(1L, "s1");
        listener.disconnected(1L, "s1");

        scheduledTask().run();

        verify(cafeSeatService).leave(1L);
    }

    @Test
    void 유예_안에_다시_연결하면_예약을_취소하고_자리를_유지한다() {
        listener.connected(1L, "s1");
        listener.disconnected(1L, "s1");
        Runnable task = scheduledTask();

        listener.connected(1L, "s2");
        verify(future).cancel(false);

        // 취소가 늦어 예약 작업이 이미 실행되어도 자리를 비우지 않는다
        task.run();
        verify(cafeSeatService, never()).leave(1L);
    }

    @Test
    void 탭_두_개_중_하나만_닫으면_예약하지_않는다() {
        listener.connected(1L, "s1");
        listener.connected(1L, "s2");
        listener.disconnected(1L, "s1");

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void 같은_연결의_종료_이벤트가_두_번_와도_한_번만_처리한다() {
        listener.connected(1L, "s1");
        listener.connected(1L, "s2");
        listener.disconnected(1L, "s1");
        listener.disconnected(1L, "s1");

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.SeatPresenceListenerTest"`
Expected: 컴파일 실패 (`SeatPresenceListener` 없음)

- [ ] **Step 4: 구현**

```java
package com.studycafe.domain.cafe.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.AbstractSubProtocolEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

// 회원별 STOMP 연결을 세고, 마지막 연결이 끊기면 유예 시간 뒤 자리를 비운다
@Component
public class SeatPresenceListener {

    private final CafeSeatService cafeSeatService;
    private final TaskScheduler scheduler;
    private final Duration gracePeriod;

    // 회원 ID → 열린 STOMP 세션 ID들 (탭 여러 개, 종료 이벤트 중복 대응)
    private final Map<Long, Set<String>> sessions = new HashMap<>();
    private final Map<Long, ScheduledFuture<?>> pending = new HashMap<>();

    public SeatPresenceListener(CafeSeatService cafeSeatService,
                                @Qualifier("seatTaskScheduler") TaskScheduler scheduler,
                                @Value("${cafe.seat.grace-period}") Duration gracePeriod) {
        this.cafeSeatService = cafeSeatService;
        this.scheduler = scheduler;
        this.gracePeriod = gracePeriod;
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Long memberId = memberId(event);
        if (memberId != null) connected(memberId, sessionId(event));
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        Long memberId = memberId(event);
        if (memberId != null) disconnected(memberId, event.getSessionId());
    }

    public synchronized void connected(Long memberId, String sessionId) {
        sessions.computeIfAbsent(memberId, id -> new HashSet<>()).add(sessionId);
        ScheduledFuture<?> scheduled = pending.remove(memberId);
        if (scheduled != null) scheduled.cancel(false);
    }

    public synchronized void disconnected(Long memberId, String sessionId) {
        Set<String> open = sessions.get(memberId);
        if (open == null || !open.remove(sessionId) || !open.isEmpty()) return;
        sessions.remove(memberId);
        pending.put(memberId, scheduler.schedule(() -> expire(memberId), Instant.now().plus(gracePeriod)));
    }

    private synchronized void expire(Long memberId) {
        pending.remove(memberId);
        // 유예 중 다시 연결했다면 그대로 둔다
        if (sessions.containsKey(memberId)) return;
        cafeSeatService.leave(memberId);
    }

    private static Long memberId(AbstractSubProtocolEvent event) {
        Principal user = event.getUser();
        return user == null ? null : Long.valueOf(user.getName());
    }

    private static String sessionId(AbstractSubProtocolEvent event) {
        return SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.service.SeatPresenceListenerTest"`
Expected: PASS (4개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/application.yaml src/main/java/com/studycafe/domain/cafe/service/SeatPresenceListener.java src/test/java/com/studycafe/domain/cafe/service/SeatPresenceListenerTest.java
git commit -m "feat: 좌석 연결 집계와 30초 유예 추가"
```

---

### Task 4: STOMP 설정, 인증, 컨트롤러 + 통합 테스트

**Files:**
- Create: `src/main/java/com/studycafe/infra/jwt/StompAuthChannelInterceptor.java`
- Create: `src/main/java/com/studycafe/global/config/WebSocketConfig.java`
- Create: `src/main/java/com/studycafe/domain/cafe/dto/TakeSeatRequest.java`, `StatusRequest.java`, `SeatError.java`
- Create: `src/main/java/com/studycafe/domain/cafe/controller/CafeSeatController.java`
- Modify: `src/main/java/com/studycafe/global/config/SecurityConfig.java` (permitAll 목록)
- Test: `src/test/java/com/studycafe/domain/cafe/controller/CafeSeatControllerTest.java`

**Interfaces:**
- Consumes: `JwtProvider.isValid/extractMemberId/generate`, `CafeSeatService` (Task 2), `SeatPresenceListener` (Task 3, 빈 `seatTaskScheduler` 필요)
- Produces: 전역 제약의 STOMP 주소 전부. 프론트 계획이 이 주소와 JSON 형식에 의존한다

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.studycafe.domain.cafe.controller;

import com.studycafe.domain.cafe.service.SeatRegistry;
import com.studycafe.domain.member.entity.Member;
import com.studycafe.domain.member.repository.MemberRepository;
import com.studycafe.infra.jwt.JwtProvider;
import com.studycafe.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CafeSeatControllerTest extends IntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SeatRegistry seatRegistry;

    @AfterEach
    void tearDown() {
        for (int i = 0; i < SeatRegistry.SEAT_COUNT; i++) {
            var occupant = seatRegistry.snapshot().get(i).occupant();
            if (occupant != null) seatRegistry.leave(occupant.memberId());
        }
        memberRepository.deleteAll();
    }

    private StompSession connect(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) connectHeaders.add("Authorization", "Bearer " + token);
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        return received;
    }

    private String tokenFor(String email, String nickname) {
        Member member = memberRepository.save(Member.of(email, "encoded-password", nickname));
        return jwtProvider.generate(member.getId());
    }

    @Test
    void 토큰_없이_연결하면_거부된다() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(Exception.class);
    }

    @Test
    void 위조된_토큰으로_연결하면_거부된다() {
        assertThatThrownBy(() -> connect("forged.token.value")).isInstanceOf(Exception.class);
    }

    @Test
    void 구독하면_현재_좌석표_10개를_받는다() throws Exception {
        StompSession session = connect(tokenFor("a@test.com", "라떼"));

        JsonNode seats = subscribe(session, "/app/cafe/seats").poll(5, TimeUnit.SECONDS);

        assertThat(seats).isNotNull();
        assertThat(seats.size()).isEqualTo(10);
        assertThat(seats.get(0).get("occupant").isNull()).isTrue();
    }

    @Test
    void 앉으면_다른_접속자에게_좌석표가_전달된다() throws Exception {
        StompSession sitter = connect(tokenFor("a@test.com", "라떼"));
        StompSession watcher = connect(tokenFor("b@test.com", "모카"));
        BlockingQueue<JsonNode> updates = subscribe(watcher, "/topic/cafe/seats");
        Thread.sleep(300); // 구독이 등록될 시간

        sitter.send("/app/cafe/seat.take", Map.of("seatId", 3));

        JsonNode seats = updates.poll(5, TimeUnit.SECONDS);
        assertThat(seats).isNotNull();
        assertThat(seats.get(3).get("occupant").get("nickname").asString()).isEqualTo("라떼");
        assertThat(seats.get(3).get("occupant").get("sittingSince").isString()).isTrue();
    }

    @Test
    void 이미_찬_자리를_고르면_본인에게만_SEAT_TAKEN이_온다() throws Exception {
        StompSession first = connect(tokenFor("a@test.com", "라떼"));
        StompSession second = connect(tokenFor("b@test.com", "모카"));
        BlockingQueue<JsonNode> errors = subscribe(second, "/user/queue/errors");
        BlockingQueue<JsonNode> firstErrors = subscribe(first, "/user/queue/errors");
        Thread.sleep(300);

        first.send("/app/cafe/seat.take", Map.of("seatId", 3));
        Thread.sleep(300);
        second.send("/app/cafe/seat.take", Map.of("seatId", 3));

        JsonNode error = errors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("code").asString()).isEqualTo("SEAT_TAKEN");
        assertThat(firstErrors.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }
}
```

Jackson 3의 `JsonNode`에서 문자열은 `asString()`, 판별은 `isString()`이다. 컴파일이 안 되면 `asText()`/`isTextual()`로 바꾼다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.controller.CafeSeatControllerTest"`
Expected: 컴파일 실패 또는 연결 실패 (`/ws` 엔드포인트 없음)

- [ ] **Step 3: 인증 인터셉터 구현**

```java
package com.studycafe.infra.jwt;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

// STOMP CONNECT 프레임의 Authorization 헤더(JWT)를 검증해 연결 사용자(회원 ID)를 정한다
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) return message;

        String header = accessor.getFirstNativeHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null || !jwtProvider.isValid(token)) {
            throw new MessagingException("UNAUTHORIZED");
        }
        Long memberId = jwtProvider.extractMemberId(token);
        accessor.setUser(new UsernamePasswordAuthenticationToken(memberId, null, List.of()));
        return message;
    }
}
```

- [ ] **Step 4: STOMP 설정 구현**

```java
package com.studycafe.global.config;

import com.studycafe.infra.jwt.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 노트북 덮기처럼 조용히 끊긴 연결을 감지하는 하트비트 (ms)
    private static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.split(","));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(seatTaskScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

    // 하트비트와 좌석 유예 예약에 함께 쓰는 스케줄러
    @Bean
    public ThreadPoolTaskScheduler seatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("seat-");
        return scheduler;
    }
}
```

- [ ] **Step 5: DTO와 컨트롤러 구현**

```java
package com.studycafe.domain.cafe.dto;

public record TakeSeatRequest(Integer seatId) {
}
```

```java
package com.studycafe.domain.cafe.dto;

public record StatusRequest(boolean studying) {
}
```

```java
package com.studycafe.domain.cafe.dto;

// 요청한 회원에게만 보내는 좌석 오류 (ErrorCode 이름)
public record SeatError(String code) {
}
```

```java
package com.studycafe.domain.cafe.controller;

import com.studycafe.domain.cafe.dto.SeatError;
import com.studycafe.domain.cafe.dto.SeatView;
import com.studycafe.domain.cafe.dto.StatusRequest;
import com.studycafe.domain.cafe.dto.TakeSeatRequest;
import com.studycafe.domain.cafe.service.CafeSeatService;
import com.studycafe.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class CafeSeatController {

    private final CafeSeatService cafeSeatService;

    // 구독하자마자 현재 좌석표를 한 번 받는다
    @SubscribeMapping("/cafe/seats")
    public List<SeatView> seats() {
        return cafeSeatService.snapshot();
    }

    @MessageMapping("/cafe/seat.take")
    public void take(TakeSeatRequest request, Principal principal) {
        cafeSeatService.take(memberId(principal), request.seatId());
    }

    @MessageMapping("/cafe/status")
    public void status(StatusRequest request, Principal principal) {
        cafeSeatService.updateStudying(memberId(principal), request.studying());
    }

    @MessageExceptionHandler(CustomException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public SeatError handle(CustomException e) {
        return new SeatError(e.getErrorCode().name());
    }

    private static Long memberId(Principal principal) {
        return Long.valueOf(principal.getName());
    }
}
```

- [ ] **Step 6: 보안 설정 수정**

`SecurityConfig.java`의 permitAll 목록에 핸드셰이크 경로를 추가한다(인증은 STOMP CONNECT에서 인터셉터가 한다).

```java
                        .requestMatchers(
                                "/api/v1/members/signup",
                                "/api/v1/members/login",
                                "/api/v1/members/refresh",
                                "/ws", "/ws/**"
                        ).permitAll()
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "com.studycafe.domain.cafe.controller.CafeSeatControllerTest"`
Expected: PASS (5개)

- [ ] **Step 8: 전체 테스트와 린트**

Run: `./gradlew test`
Expected: 모두 통과 (기존 테스트 포함). `build.gradle`에 checkstyle 플러그인이 없어 `checkstyleMain`은 실행하지 않는다

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/studycafe/infra/jwt/StompAuthChannelInterceptor.java src/main/java/com/studycafe/global/config src/main/java/com/studycafe/domain/cafe src/test/java/com/studycafe/domain/cafe
git commit -m "feat: 카페 좌석 STOMP 엔드포인트와 JWT 연결 인증 추가"
```


워크로그(`docs/WORKLOG.md`)와 설계 문서는 사용자가 직접 커밋하기로 한 미커밋 변경이 있으므로 이 계획에서는 건드리지 않는다.
