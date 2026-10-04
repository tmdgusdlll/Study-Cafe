# Study-Cafe 백엔드 작업 로그

> 백엔드 전용 로그. 프론트는 `../../frontend/docs/WORKLOG.md` 참고. 세부 구현 태스크는 `docs/superpowers/plans/*.md` 참고.

## 현재 위치 (한눈 요약)

- **백엔드**: 회원 인증 / 공부 세션 / 포인트(적립·차감·이력) / 상점(목록·구매·보유) 완료
- **레포 구조**: `Study-Cafe/backend`(레포1, →Render) + `Study-Cafe/frontend`(레포2, →Vercel)

## 다음 할 일 (우선순위 순)

- [x] 백엔드: 내 정보 조회 API `GET /api/v1/members/me` — 프론트 홈에 닉네임 표시용 (로그인 응답·JWT에 닉네임 없음)
- [x] 백엔드: 로컬 CORS 허용 출처를 Vite 개발 서버(`http://localhost:5173`)로 변경
- [x] 백엔드: 회원 요청 DTO 입력 검증(`@Valid`) — 이메일 형식, 비밀번호 8~20자, 닉네임 2~10자
- [ ] 백엔드: 카페 손님 1:1 대화 — 실제 사용자 간 WebSocket + STOMP, NPC는 RAG(npc) 도메인과 연결. 프론트 `docs/main-screen-design.md` "다음 작업"에서 설계부터
- [ ] 백엔드: 포트원 V2 결제(payment) 도메인 — `docs/superpowers/plans/2026-07-06-points-portone.md`
- [ ] 백엔드: RAG(npc) 도메인 — `docs/superpowers/plans/2026-07-06-spring-ai-rag.md`
- [x] 백엔드: cafe(좌석) 도메인 — 좌석 실시간 점유. 설계 `docs/superpowers/specs/2026-10-04-cafe-seats-design.md`, 계획 `docs/superpowers/plans/2026-10-04-cafe-seats-backend.md`
- [ ] 백엔드: 주문 내역 저장 (프론트 메뉴 주문 방식 대응) — 프론트는 카페 메뉴를 여러 잔 골라 합산 시간으로 공부를 시작함. 메뉴 이름·수량은 프론트에서만 쓰고 있어 서버 저장이 필요함
  - [ ] 메뉴 정의: `menu` 테이블(id, 이름, 시간(분))과 목록 조회 API `GET /api/v1/menus`. 메뉴별 시간 정책은 서버가 쥔다 (프론트 `MENU` 상수를 대체). 초기값: 에스프레소 30분 / 아이스 아메리카노 60분 / 카페라떼 120분 / 시그니처 라떼 180분
  - [ ] 주문 항목 저장: `study_sessions` 1 : N `session_order_items`(session_id, menu_id, quantity, 주문 당시 메뉴명·시간 스냅샷). 메뉴를 나중에 수정해도 과거 기록이 바뀌지 않도록 스냅샷 보관
  - [ ] 세션 시작 API 확장: `POST /api/v1/sessions/start`에 `{ items: [{ menuId, quantity }] }` 추가(현재는 요청 본문 없음). 목표 시간은 서버가 항목에서 직접 계산해 `goalMinutes`로 `study_sessions`에 저장하고 응답에 포함. 검증: 항목 1개 이상, 수량 1~9(프론트 상한과 일치), 존재하지 않는 menuId는 400
  - [ ] 주문 내역 조회 API: `GET /api/v1/sessions` (내 세션 목록, 페이징) — 세션별 주문 항목·목표 시간·실제 공부 시간·적립 포인트 포함
  - [ ] 테스트: 시간 합산, 수량·메뉴 검증 실패, 진행 중 세션이 있을 때 재시작 거부(기존 동작 유지)
- [ ] 백엔드: 세션 포인트 규칙 변경 — **확정: 10분마다 500P, 10분 미만은 0P, 10분 단위 미만은 버림**(예: 19분 → 500P, 25분 → 1000P). 현재 서버는 1분당 10P·1분 이상이면 적립(`StudySessionService.end`)이라 수정 필요. 프론트 `pointsFor`는 이미 이 규칙으로 변경됨. 500P는 임시값 — 상점 아이템 가격 확정 후 재조정
- [ ] 백엔드: 세션 일시정지·목표 시간 지원 — 프론트는 일시정지/재개와 목표 시간(달성 시 "목표 달성" 상태)을 지원하지만 서버 세션은 시작/종료만 있고 일시정지 구간을 기록하지 않음. 서버가 공부 시간을 계산하려면 일시정지 구간(또는 누적 일시정지 시간)을 저장해야 함. 프론트 연동 방식(주문 내역과 별개로 먼저 정할지)은 협의 필요

## 작업 일지 (최신순)

### 2026-10-04
- 카페 좌석 실시간 점유: STOMP `/ws`(CONNECT 시 JWT 검증), 메모리 좌석 10석(`SeatRegistry`), 연결 끊김 30초 유예(`SeatPresenceListener`), 좌석표 전체 브로드캐스트. 테스트 54개 통과(새 23개)
- 포인트 규칙 확정(10분마다 500P), 주문 내역 저장 할 일 정리

### 2026-10-01
- 프론트 연동 준비: `GET /api/v1/members/me` 추가, 로컬 CORS를 5173으로 변경, 회원 요청 DTO에 `@Valid` 검증 + 검증 실패 시 400 `INVALID_INPUT`(필드 메시지 포함) 응답. 테스트 31개 통과
- 레포 재구성: 기존 백엔드를 `Study-Cafe/backend/`로 이동(히스토리·리모트 보존), 프론트용 `Study-Cafe/frontend/` 신설(별도 레포)
- 배포 방향 확정: 프론트=Vercel / 백엔드=Render (레포 2개 분리)
