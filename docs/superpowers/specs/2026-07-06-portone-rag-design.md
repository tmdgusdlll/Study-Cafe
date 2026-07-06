# 카페 공부 타이머 - 포트원 + RAG 설계

## 개요

카페 공부 타이머 앱의 포인트/결제 시스템(포트원)과 AI 기능(Spring AI RAG) 설계.

---

## 1. 전체 아키텍처

```
[프론트엔드]
     ↓ REST / WebSocket
[Spring Boot - 메인 서버]
  ├─ 인증/회원 관리
  ├─ 타이머/세션 관리
  ├─ 포인트 시스템 + 포트원 결제
  ├─ WebSocket (카페 채팅/NPC 대화)
  └─ Spring AI 모듈
        ├─ NPC RAG (질문 → 벡터 검색 → LLM 답변)
        └─ 세션 피드백 RAG (종료 → 과거 기록 검색 → 피드백)
             ↓ 임베딩/벡터 검색
[PostgreSQL + PGVector]                    [Kafka]
  일반 데이터 + 벡터 데이터 통합              이벤트 처리
  (세션 기록, 포인트, 회원 정보,
   사용자 PDF 청크, 앱 기본 지식베이스,
   세션 기록 임베딩)
```

별도 벡터DB 서비스 없이 PostgreSQL 하나로 통합.

---

## 2. 포인트 시스템 + 포트원 결제

### 포인트 획득

| 조건 | 포인트 |
|---|---|
| 세션 달성 | 설정 시간(분) × 1P |
| 3일 연속 달성 보너스 | × 1.5배 |
| 포트원 직접 충전 | 1,000원 = 1,000P |

### 포인트 소비 (상점 아이템)

| 카테고리 | 포인트 범위 |
|---|---|
| 카페 배경 테마 | 500P ~ 2,000P |
| 앰비언트 사운드 팩 | 300P ~ 1,000P |
| NPC 캐릭터 팩 | 800P ~ 3,000P |

### 포트원 결제 흐름

```
사용자가 포인트 충전 요청
→ Spring Boot가 포트원 결제 요청 생성
→ 포트원 결제창 (카드/카카오페이 등)
→ 결제 완료 웹훅 수신
→ 포인트 PostgreSQL 적립
```

### DB 테이블 (추가 필요)

- `user_points`: 사용자별 포인트 잔액
- `point_history`: 포인트 획득/소비 내역
- `user_items`: 사용자가 보유한 아이템
- `store_items`: 상점 아이템 목록 (카테고리, 가격, 리소스 경로)
- `payments`: 포트원 결제 내역 (웹훅 검증용)

---

## 3. Spring AI RAG 설계

### 의존성

```gradle
implementation 'org.springframework.ai:spring-ai-openai-spring-boot-starter'
implementation 'org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter'
```

### PGVector 메타데이터 필터링으로 데이터 분리

별도 네임스페이스 대신 메타데이터 필터로 데이터 구분:

| 데이터 종류 | 메타데이터 |
|---|---|
| 앱 기본 지식 | `type=global, domain=cs` |
| 사용자 업로드 PDF | `type=user, userId=123` |
| 세션 기록 | `type=session, userId=123` |

### 3-1. NPC 대화 RAG

**지식베이스 구성:**

| 종류 | 내용 | 적재 시점 |
|---|---|---|
| 앱 기본 지식 | 분야별 학습 문서 (CS, 수학, 영어 등) | 서버 시작 시 PGVector 적재 |
| 사용자 업로드 | PDF → 청크 분리 → 임베딩 | 사용자가 파일 업로드 시 |

**대화 흐름:**
```
사용자 질문
→ global + user 메타데이터 필터로 동시 검색
→ 관련 청크 Top-K 추출
→ LLM 프롬프트에 컨텍스트로 포함
→ LLM 답변 생성 → WebSocket으로 스트리밍 전달
```

### 3-2. 세션 종료 피드백 RAG

**세션 기록 저장:**
```
타이머 종료 + 달성 여부 입력
→ 세션 정보를 자연어 텍스트로 변환
  예) "2026-07-06 21:00, CS 공부, 2시간, 달성"
→ 임베딩 후 PGVector 저장 (type=session, userId=123)
```

**피드백 생성:**
```
→ 이번 세션과 유사한 과거 세션 검색 (같은 시간대, 같은 분야)
→ LLM에 과거 패턴 + 이번 세션 전달
→ 개인화된 피드백 생성
```

**피드백 예시:**
- 달성 시: "저녁 9시 CS 공부 달성률이 83%예요. 오늘도 해냈네요! 이 시간대가 골든타임이에요."
- 미달성 시: "저녁 9시엔 자주 힘들어하시네요. 시작 시간을 1시간 앞당겨보는 건 어떨까요?"

---

## 4. 기술 스택 요약

| 항목 | 선택 |
|---|---|
| 메인 서버 | Spring Boot 4.1.0 (Java 17) |
| DB | PostgreSQL + PGVector |
| 메시지 브로커 | Kafka |
| AI 프레임워크 | Spring AI |
| LLM | OpenAI API (or Claude API) |
| 결제 | 포트원 V2 |

---

## 5. 미결 사항

- LLM 제공사 선택: OpenAI vs Claude (비용/성능 비교 필요)
- 포인트 배율 및 상점 가격 최종 결정
- PDF 업로드 용량 제한 및 청크 크기 설정
