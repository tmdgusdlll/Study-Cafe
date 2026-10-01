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
- [ ] 백엔드: cafe(배경/좌석) 도메인 — 계획 문서부터 수립 필요

## 작업 일지 (최신순)

### 2026-10-01
- 프론트 연동 준비: `GET /api/v1/members/me` 추가, 로컬 CORS를 5173으로 변경, 회원 요청 DTO에 `@Valid` 검증 + 검증 실패 시 400 `INVALID_INPUT`(필드 메시지 포함) 응답. 테스트 31개 통과
- 레포 재구성: 기존 백엔드를 `Study-Cafe/backend/`로 이동(히스토리·리모트 보존), 프론트용 `Study-Cafe/frontend/` 신설(별도 레포)
- 배포 방향 확정: 프론트=Vercel / 백엔드=Render (레포 2개 분리)
