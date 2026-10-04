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
import org.springframework.messaging.simp.stomp.ConnectionLostException;
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
        assertThatThrownBy(() -> connect(null)).hasRootCauseInstanceOf(ConnectionLostException.class);
    }

    @Test
    void 위조된_토큰으로_연결하면_거부된다() {
        assertThatThrownBy(() -> connect("forged.token.value")).hasRootCauseInstanceOf(ConnectionLostException.class);
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
