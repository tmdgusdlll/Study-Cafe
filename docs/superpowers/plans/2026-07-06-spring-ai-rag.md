# Spring AI RAG 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring AI와 PGVector를 사용해 NPC 대화 RAG(글로벌 지식 + 사용자 PDF 혼합)와 세션 종료 피드백 RAG를 구현한다.

**Architecture:** PGVector의 메타데이터 필터(`type=global` / `type=user` / `type=session`)로 데이터를 논리적으로 분리한다. NPC RAG는 `QuestionAnswerAdvisor`로 파이프라인을 자동화하고, 세션 피드백은 과거 세션 기록을 검색해 LLM 프롬프트에 주입한다. WebSocket STOMP로 NPC 답변을 스트리밍한다.

**Tech Stack:** Spring Boot 4.1.0, Java 17, Spring AI 1.0.0, Google Gemini 무료 티어(일 1,500 요청), PostgreSQL + PGVector, WebSocket STOMP, Lombok

## Spring AI 핵심 개념 지도

> 이 계획을 읽기 전에 전체 그림을 파악해두면 각 태스크가 훨씬 잘 이해된다.
>
> ```
> [텍스트 / PDF]
>      ↓ DocumentReader (읽기)
> [Document 리스트]
>      ↓ TokenTextSplitter (청크 분리)
> [Document 청크들] ─── metadata: {type, domain, userId}
>      ↓ VectorStore.add()
>      ↓ EmbeddingModel (텍스트 → 숫자 벡터)
> [PGVector 테이블] ──────────────────────────────┐
>                                                  │ similaritySearch()
> [사용자 질문]                                   │
>      ↓ ChatClient.prompt().advisors()            │
> [QuestionAnswerAdvisor] ──── FilterExpression ──┘
>      ↓ 검색된 청크를 프롬프트에 삽입
> [LLM (gemini-1.5-flash)]
>      ↓
> [답변] → WebSocket STOMP → 프론트엔드
> ```
>
> **핵심 클래스 한 줄 요약:**
> - `EmbeddingModel`: 텍스트 → float[] 벡터 변환 (Gemini `text-embedding-004`, 768차원)
> - `VectorStore`: 벡터 저장소 (PgVectorStore) — add(), similaritySearch()
> - `ChatClient`: LLM 호출 인터페이스 — prompt().user().advisors().call()
> - `QuestionAnswerAdvisor`: RAG 파이프라인 자동화 — 질문 임베딩 → 검색 → 컨텍스트 삽입
> - `Document`: Spring AI의 문서 단위 — content(String) + metadata(Map)
> - `TokenTextSplitter`: 문서를 토큰 기준으로 청크 분리
> - `FilterExpressionBuilder`: 메타데이터 필터 DSL (`b.eq("type", "global")`)

## Global Constraints

- 언어: Java 17, Jakarta EE (`import jakarta.*`)
- 패키지 루트: `com.studycafe`
- 주석: 한글
- DB: PostgreSQL + PGVector (`localhost:5432`, db=`studycafe`)
- Spring AI BOM: `1.0.0`
- Gemini 임베딩 모델: `text-embedding-004` (차원: 768)
- Gemini 채팅 모델: `gemini-1.5-flash` (무료 티어: 일 1,500 요청, 초과 시 429 에러)
- API 키: Google AI Studio(aistudio.google.com)에서 무료 발급 → 환경변수 `GOOGLE_AI_API_KEY`
- 테스트 명령: `./gradlew test --tests "패키지.클래스명"`
- Lombok: `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@RequiredArgsConstructor`
- FK-only 엔티티: JPA 연관관계 대신 `Long memberId` 방식 사용
- API 응답: `ApiResponse<T>` 래퍼 사용

---

### Task 1: Spring AI 의존성 추가 및 PGVector 설정

> **이 태스크에서 배우는 것:**
> Spring AI는 BOM(Bill of Materials)으로 버전을 통합 관리한다. PGVector는 PostgreSQL 확장이므로
> DB 연결 설정 외에 `initialize-schema: true`만 추가하면 Spring AI가 `vector_store` 테이블을
> 자동 생성한다. `dimensions: 768`은 Gemini `text-embedding-004` 모델의 벡터 차원 수다.
>
> **Gemini를 OpenAI 스타터로 쓰는 이유:** Gemini는 OpenAI API 호환 엔드포인트
> (`https://generativelanguage.googleapis.com/v1beta/openai`)를 제공한다.
> `spring-ai-openai-spring-boot-starter`의 `base-url`만 Gemini 엔드포인트로 바꾸면
> 의존성 추가 없이 바로 Gemini를 사용할 수 있다.

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/studycafe/infra/ai/config/SpringAiConfig.java`
- Create: `src/main/java/com/studycafe/global/config/WebSocketConfig.java`

**Interfaces:**
- Produces: `ChatClient` 빈, `VectorStore` 빈 (자동 설정), WebSocket `/ws` 엔드포인트

- [ ] **Step 1: build.gradle에 Spring AI + WebSocket 의존성 추가**

```groovy
// build.gradle
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

dependencyManagement {
    imports {
        // Spring AI BOM으로 모든 spring-ai 라이브러리 버전을 통합 관리
        mavenBom "org.springframework.ai:spring-ai-bom:1.0.0"
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-kafka'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-websocket'   // WebSocket STOMP

    // Spring AI - OpenAI 스타터 (Gemini OpenAI 호환 엔드포인트를 통해 사용)
    implementation 'org.springframework.ai:spring-ai-openai-spring-boot-starter'
    // Spring AI - PGVector (VectorStore → PostgreSQL에 벡터 저장)
    implementation 'org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter'
    // Spring AI - Tika (PDF/Word/Excel → Document 변환)
    implementation 'org.springframework.ai:spring-ai-tika-document-reader'

    runtimeOnly 'org.postgresql:postgresql'           // MySQL → PostgreSQL로 교체
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-actuator-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testCompileOnly 'org.projectlombok:lombok'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 2: application.yaml에 Spring AI + PGVector 설정 추가**

```yaml
# src/main/resources/application.yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/studycafe
    username: studycafe
    password: studycafe
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

  ai:
    openai:
      api-key: ${GOOGLE_AI_API_KEY}    # Google AI Studio에서 발급 (aistudio.google.com)
      # Gemini의 OpenAI 호환 엔드포인트 — spring-ai-openai-starter를 그대로 사용
      base-url: https://generativelanguage.googleapis.com/v1beta/openai
      chat:
        options:
          model: gemini-1.5-flash      # 무료 티어: 일 1,500 요청
      embedding:
        options:
          model: text-embedding-004    # 768차원, Gemini 임베딩 모델

    vectorstore:
      pgvector:
        initialize-schema: true    # 첫 실행 시 vector_store 테이블 자동 생성
        dimensions: 768            # text-embedding-004 출력 차원
        distance-type: COSINE_DISTANCE  # 의미적 유사도에는 코사인이 적합
        index-type: HNSW           # 근사 최근접 이웃 검색 인덱스 (빠름)
```

- [ ] **Step 3: SpringAiConfig 작성 — ChatClient 빈 수동 등록**

```java
// src/main/java/com/studycafe/infra/ai/config/SpringAiConfig.java
package com.studycafe.infra.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    // ChatModel은 spring-ai-openai-spring-boot-starter가 자동 등록한 빈 (Gemini 엔드포인트 사용)
    // ChatClient는 ChatModel을 감싸는 고수준 인터페이스 (어드바이저, 스트리밍 지원)
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
```

- [ ] **Step 4: WebSocketConfig 작성**

```java
// src/main/java/com/studycafe/global/config/WebSocketConfig.java
package com.studycafe.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 클라이언트가 WebSocket 연결을 맺는 엔드포인트
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic/** : 브로커가 구독자에게 메시지를 브로드캐스트하는 prefix
        registry.enableSimpleBroker("/topic");
        // /app/** : 클라이언트 → 서버 메시지를 Controller로 라우팅하는 prefix
        registry.setApplicationDestinationPrefixes("/app");
    }
}
```

- [ ] **Step 5: 빌드 확인**

```bash
./gradlew build -x test
```

기대 결과: `BUILD SUCCESSFUL` — 의존성 다운로드 및 컴파일 성공

- [ ] **Step 6: 커밋**

```bash
git add build.gradle src/main/resources/application.yaml \
        src/main/java/com/studycafe/infra/ai/ \
        src/main/java/com/studycafe/global/config/WebSocketConfig.java
git commit -m "feat: Spring AI 1.0.0 + PGVector + WebSocket 의존성 및 설정 추가"
```

---

### Task 2: 글로벌 문서 적재 (서버 시작 시 앱 기본 지식 → VectorStore)

> **이 태스크에서 배우는 것:**
>
> **Document**: Spring AI의 기본 데이터 단위. `content`(String)와 `metadata`(Map<String, Object>)로 구성된다.
> 메타데이터가 나중에 검색 필터의 기준이 되므로 일관된 키를 사용하는 것이 중요하다.
>
> **TokenTextSplitter**: 긴 문서를 LLM이 처리할 수 있는 크기(토큰 수 기준)의 청크로 자른다.
> `chunkSize=512`는 청크당 최대 토큰 수, `overlap=50`은 문맥 연결을 위해 앞 청크와 겹치는 토큰 수다.
>
> **VectorStore.add()**: 내부적으로 EmbeddingModel을 호출해 각 청크를 벡터로 변환한 뒤 PGVector에 저장한다.
> 이 과정에서 OpenAI API가 호출되므로 서버 시작 시 한 번만 실행하도록 중복 체크가 필요하다.

**Files:**
- Create: `src/main/resources/knowledge/cs-basics.md` (샘플 지식 파일)
- Create: `src/main/java/com/studycafe/infra/ai/document/GlobalDocumentLoader.java`
- Test: `src/test/java/com/studycafe/infra/ai/document/GlobalDocumentLoaderTest.java`

**Interfaces:**
- Consumes: `VectorStore` 빈
- Produces: 서버 시작 시 `resources/knowledge/` 파일들이 `{type=global}` 메타데이터로 VectorStore에 적재됨

- [ ] **Step 1: 샘플 지식 파일 작성**

```markdown
<!-- src/main/resources/knowledge/cs-basics.md -->
# 자료구조 기초

## 배열 (Array)
배열은 연속된 메모리 공간에 동일한 타입의 원소를 저장하는 자료구조다.
접근 시간복잡도 O(1), 삽입/삭제 O(n).

## 연결 리스트 (Linked List)
각 노드가 데이터와 다음 노드의 포인터를 가진다.
삽입/삭제 O(1), 접근 O(n). 동적 크기 조절에 유리하다.

## 스택 (Stack)
LIFO(Last In First Out) 구조. push/pop 연산 O(1).
재귀 호출 스택, 괄호 검사, DFS에 사용된다.

## 큐 (Queue)
FIFO(First In First Out) 구조. enqueue/dequeue 연산 O(1).
BFS, 작업 스케줄링에 사용된다.

## 해시 테이블 (Hash Table)
키를 해시 함수로 변환해 배열 인덱스로 사용한다.
평균 O(1) 검색/삽입/삭제. 충돌 해결이 핵심 과제다.
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/infra/ai/document/GlobalDocumentLoaderTest.java
package com.studycafe.infra.ai.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GlobalDocumentLoaderTest {

    @InjectMocks
    private GlobalDocumentLoader globalDocumentLoader;

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("글로벌 문서 적재 - type=global 메타데이터가 붙어 VectorStore에 저장된다")
    void load_storesDocumentsWithGlobalMetadata() throws Exception {
        globalDocumentLoader.loadGlobalKnowledge();

        // type=global 메타데이터를 가진 Document가 저장되었는지 검증
        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().allMatch(doc -> "global".equals(doc.getMetadata().get("type")))
        ));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.infra.ai.document.GlobalDocumentLoaderTest"
```

기대 결과: FAIL — `GlobalDocumentLoader` 클래스 없음

- [ ] **Step 4: GlobalDocumentLoader 구현**

```java
// src/main/java/com/studycafe/infra/ai/document/GlobalDocumentLoader.java
package com.studycafe.infra.ai.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalDocumentLoader {

    private final VectorStore vectorStore;

    // 서버 완전히 시작된 후 실행 (DB 연결 보장)
    @EventListener(ApplicationReadyEvent.class)
    public void loadGlobalKnowledge() throws IOException {
        List<Document> documents = readKnowledgeFiles();
        if (documents.isEmpty()) {
            log.warn("resources/knowledge/ 에 적재할 파일이 없습니다");
            return;
        }

        // 512 토큰 단위로 청크 분리, 50 토큰 오버랩으로 문맥 연결
        TokenTextSplitter splitter = new TokenTextSplitter(512, 50, 5, 10000, true);
        List<Document> chunks = splitter.apply(documents);

        log.info("글로벌 지식 적재: {} 청크 → VectorStore", chunks.size());
        vectorStore.add(chunks);
    }

    private List<Document> readKnowledgeFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:knowledge/*.md");

        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String fileName = resource.getFilename();
            // type=global : 이후 검색 시 FilterExpression으로 이 데이터만 걸러낼 수 있다
            Document doc = new Document(content, Map.of(
                    "type", "global",
                    "source", fileName != null ? fileName : "unknown"
            ));
            documents.add(doc);
        }
        return documents;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.infra.ai.document.GlobalDocumentLoaderTest"
```

기대 결과: `BUILD SUCCESSFUL`, 1 test passed

- [ ] **Step 6: 커밋**

```bash
git add src/main/resources/knowledge/ src/main/java/com/studycafe/infra/ai/document/GlobalDocumentLoader.java \
        src/test/java/com/studycafe/infra/ai/document/GlobalDocumentLoaderTest.java
git commit -m "feat: 서버 시작 시 글로벌 지식 파일 → PGVector 자동 적재"
```

---

### Task 3: NPC RAG 서비스 핵심 구현

> **이 태스크에서 배우는 것:**
>
> **QuestionAnswerAdvisor**: RAG의 핵심. ChatClient 어드바이저로 등록하면 LLM 호출 전에
> 자동으로 ① 질문을 임베딩 → ② VectorStore 검색 → ③ 검색된 청크를 프롬프트에 삽입한다.
> 직접 VectorStore를 호출하고 프롬프트를 조립할 필요가 없어진다.
>
> **FilterExpressionBuilder**: PGVector 메타데이터 필터 DSL.
> SQL의 WHERE 조건처럼 동작한다.
> ```java
> // type == 'global' OR (type == 'user' AND userId == '1')
> b.or(
>     b.eq("type", "global"),
>     b.and(b.eq("type", "user"), b.eq("userId", "1"))
> ).build()
> ```
>
> **SearchRequest**: VectorStore 검색 파라미터.
> `topK(5)`는 가장 유사한 청크 5개를 가져온다.

**Files:**
- Create: `src/main/java/com/studycafe/domain/npc/service/NpcRagService.java`
- Create: `src/main/java/com/studycafe/domain/npc/dto/NpcChatRequest.java`
- Create: `src/main/java/com/studycafe/domain/npc/dto/NpcChatResponse.java`
- Test: `src/test/java/com/studycafe/domain/npc/service/NpcRagServiceTest.java`

**Interfaces:**
- Consumes: `ChatClient` 빈, `VectorStore` 빈
- Produces:
  - `NpcRagService.chat(memberId, question): String` — 동기 응답
  - `NpcRagService.chatStream(memberId, question): Flux<String>` — 스트리밍 응답

- [ ] **Step 1: NpcChatRequest / NpcChatResponse DTO 작성**

```java
// src/main/java/com/studycafe/domain/npc/dto/NpcChatRequest.java
package com.studycafe.domain.npc.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NpcChatRequest {
    private Long memberId;
    private String question;
}
```

```java
// src/main/java/com/studycafe/domain/npc/dto/NpcChatResponse.java
package com.studycafe.domain.npc.dto;

import lombok.Getter;

@Getter
public class NpcChatResponse {
    private final String answer;

    public NpcChatResponse(String answer) {
        this.answer = answer;
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/npc/service/NpcRagServiceTest.java
package com.studycafe.domain.npc.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class NpcRagServiceTest {

    @InjectMocks
    private NpcRagService npcRagService;

    @Mock
    private ChatClient chatClient;

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("NPC 채팅 - 질문에 대한 답변을 반환한다")
    void chat_returnsAnswer() {
        // ChatClient는 빌더 패턴이므로 체이닝 Mock을 설정해야 한다
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.advisors(any())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);
        given(callResponseSpec.content()).willReturn("스택은 LIFO 구조입니다.");

        String answer = npcRagService.chat(1L, "스택이 뭐야?");

        assertThat(answer).isEqualTo("스택은 LIFO 구조입니다.");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.npc.service.NpcRagServiceTest"
```

기대 결과: FAIL — `NpcRagService` 클래스 없음

- [ ] **Step 4: NpcRagService 구현**

```java
// src/main/java/com/studycafe/domain/npc/service/NpcRagService.java
package com.studycafe.domain.npc.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class NpcRagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    // 동기 응답: REST API 또는 간단한 요청에 사용
    public String chat(Long memberId, String question) {
        return chatClient.prompt()
                .user(question)
                .advisors(buildRagAdvisor(memberId))
                .call()
                .content();
    }

    // 스트리밍 응답: WebSocket으로 실시간 타이핑 효과에 사용
    public Flux<String> chatStream(Long memberId, String question) {
        return chatClient.prompt()
                .user(question)
                .advisors(buildRagAdvisor(memberId))
                .stream()
                .content();
    }

    // RAG 어드바이저 생성: 해당 멤버의 문서 + 글로벌 문서만 검색
    private QuestionAnswerAdvisor buildRagAdvisor(Long memberId) {
        Filter.Expression filter = buildFilter(memberId);
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(5)                      // 가장 유사한 청크 5개 검색
                .filterExpression(filter)
                .build();
        return new QuestionAnswerAdvisor(vectorStore, searchRequest);
    }

    // type == 'global' OR (type == 'user' AND userId == '{memberId}')
    private Filter.Expression buildFilter(Long memberId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.or(
                b.eq("type", "global"),
                b.and(
                        b.eq("type", "user"),
                        b.eq("userId", String.valueOf(memberId))
                )
        ).build();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.npc.service.NpcRagServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 1 test passed

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/studycafe/domain/npc/ src/test/java/com/studycafe/domain/npc/
git commit -m "feat: NPC RAG 서비스 구현 (QuestionAnswerAdvisor + 메타데이터 필터)"
```

---

### Task 4: 사용자 PDF 업로드 처리

> **이 태스크에서 배우는 것:**
>
> **TikaDocumentReader**: Apache Tika를 사용해 PDF, Word, Excel 등 다양한 포맷을 텍스트로 변환한다.
> `Resource` 객체를 받으므로 파일 시스템, classpath, HTTP URL 모두 지원한다.
>
> 사용자가 업로드한 PDF는 `{type=user, userId=123}` 메타데이터로 저장한다.
> 나중에 NpcRagService의 FilterExpression이 이 userId로 해당 사용자 문서만 필터링한다.

**Files:**
- Create: `src/main/java/com/studycafe/infra/ai/document/UserDocumentService.java`
- Create: `src/main/java/com/studycafe/infra/ai/document/UserDocumentController.java`
- Test: `src/test/java/com/studycafe/infra/ai/document/UserDocumentServiceTest.java`

**Interfaces:**
- Consumes: `VectorStore` 빈, `MultipartFile`
- Produces:
  - `UserDocumentService.upload(memberId, file)` — PDF → 청크 → VectorStore 저장
  - `POST /api/v1/documents/upload` — 파일 업로드 REST 엔드포인트

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/infra/ai/document/UserDocumentServiceTest.java
package com.studycafe.infra.ai.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserDocumentServiceTest {

    @InjectMocks
    private UserDocumentService userDocumentService;

    @Mock
    private VectorStore vectorStore;

    @Test
    @DisplayName("PDF 업로드 - type=user, userId 메타데이터가 붙어 VectorStore에 저장된다")
    void upload_storesDocumentsWithUserMetadata() throws Exception {
        // 실제 PDF 대신 텍스트 파일로 테스트 (TikaDocumentReader는 통합 테스트에서 검증)
        MultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "스택은 LIFO 구조다.".getBytes()
        );

        userDocumentService.upload(1L, file);

        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.stream().allMatch(doc ->
                        "user".equals(doc.getMetadata().get("type")) &&
                        "1".equals(doc.getMetadata().get("userId"))
                )
        ));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.infra.ai.document.UserDocumentServiceTest"
```

기대 결과: FAIL — `UserDocumentService` 클래스 없음

- [ ] **Step 3: UserDocumentService 구현**

```java
// src/main/java/com/studycafe/infra/ai/document/UserDocumentService.java
package com.studycafe.infra.ai.document;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserDocumentService {

    private final VectorStore vectorStore;

    public void upload(Long memberId, MultipartFile file) throws IOException {
        // Tika가 PDF/Word 등 다양한 포맷을 자동 감지해서 텍스트로 변환
        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> rawDocs = reader.get();

        // 각 Document에 사용자 식별 메타데이터 부착
        List<Document> taggedDocs = rawDocs.stream()
                .map(doc -> new Document(doc.getText(), Map.of(
                        "type", "user",
                        "userId", String.valueOf(memberId),
                        "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown"
                )))
                .toList();

        // 청크 분리 후 저장
        TokenTextSplitter splitter = new TokenTextSplitter(512, 50, 5, 10000, true);
        List<Document> chunks = splitter.apply(taggedDocs);
        vectorStore.add(chunks);
    }
}
```

- [ ] **Step 4: UserDocumentController 구현**

```java
// src/main/java/com/studycafe/infra/ai/document/UserDocumentController.java
package com.studycafe.infra.ai.document;

import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class UserDocumentController {

    private final UserDocumentService userDocumentService;

    @PostMapping("/upload")
    public ApiResponse<Void> upload(
            @RequestParam Long memberId,
            @RequestParam("file") MultipartFile file) throws IOException {
        userDocumentService.upload(memberId, file);
        return ApiResponse.ok(null);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.infra.ai.document.UserDocumentServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 1 test passed

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/studycafe/infra/ai/document/UserDocumentService.java \
        src/main/java/com/studycafe/infra/ai/document/UserDocumentController.java \
        src/test/java/com/studycafe/infra/ai/document/UserDocumentServiceTest.java
git commit -m "feat: 사용자 PDF 업로드 → PGVector 저장 (TikaDocumentReader)"
```

---

### Task 5: NPC WebSocket STOMP 연동

> **이 태스크에서 배우는 것:**
>
> **STOMP over WebSocket**: 단순 WebSocket은 raw 메시지를 주고받는다.
> STOMP(Simple Text Oriented Messaging Protocol)는 그 위에서 동작하는 메시징 프로토콜로
> `/topic`, `/queue`, `/app` 같은 목적지 개념과 구독(subscribe) 모델을 추가한다.
>
> **스트리밍과 WebSocket**: NPC 답변은 LLM이 토큰을 하나씩 생성한다.
> `Flux<String>`으로 받은 스트림을 `/topic/npc/{sessionId}`에 토큰 단위로 발행하면
> 프론트엔드가 타이핑 효과로 실시간 수신할 수 있다.
>
> **흐름:**
> ```
> 클라이언트 → STOMP SEND /app/npc/chat (질문)
>            ← STOMP MESSAGE /topic/npc/{sessionId} (답변 토큰들)
>            ← STOMP MESSAGE /topic/npc/{sessionId} (완료 신호)
> ```

**Files:**
- Create: `src/main/java/com/studycafe/domain/npc/controller/NpcWebSocketController.java`
- Create: `src/main/java/com/studycafe/domain/npc/controller/NpcRestController.java`

**Interfaces:**
- Consumes: `NpcRagService.chatStream(memberId, question): Flux<String>`
- Produces:
  - `@MessageMapping("/npc/chat")` — STOMP 메시지 핸들러
  - `GET /api/v1/npc/chat` — REST 동기 응답 (테스트용)

- [ ] **Step 1: NpcWebSocketController 구현**

```java
// src/main/java/com/studycafe/domain/npc/controller/NpcWebSocketController.java
package com.studycafe.domain.npc.controller;

import com.studycafe.domain.npc.dto.NpcChatRequest;
import com.studycafe.domain.npc.service.NpcRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class NpcWebSocketController {

    private final NpcRagService npcRagService;
    private final SimpMessagingTemplate messagingTemplate;

    // 클라이언트가 /app/npc/chat 으로 메시지를 보내면 이 메서드가 실행된다
    @MessageMapping("/npc/chat")
    public void chat(NpcChatRequest request) {
        String destination = "/topic/npc/" + request.getMemberId();

        // Flux 스트림을 구독하여 토큰이 생성될 때마다 WebSocket으로 전송
        npcRagService.chatStream(request.getMemberId(), request.getQuestion())
                .subscribe(
                        token -> messagingTemplate.convertAndSend(destination, token),
                        error -> messagingTemplate.convertAndSend(destination, "[ERROR]"),
                        () -> messagingTemplate.convertAndSend(destination, "[DONE]")
                );
    }
}
```

- [ ] **Step 2: NpcRestController 구현 (REST 테스트용)**

```java
// src/main/java/com/studycafe/domain/npc/controller/NpcRestController.java
package com.studycafe.domain.npc.controller;

import com.studycafe.domain.npc.dto.NpcChatResponse;
import com.studycafe.domain.npc.service.NpcRagService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/npc")
@RequiredArgsConstructor
public class NpcRestController {

    private final NpcRagService npcRagService;

    // 동기 응답 — Postman 테스트 등에 사용
    @GetMapping("/chat")
    public ApiResponse<NpcChatResponse> chat(
            @RequestParam Long memberId,
            @RequestParam String question) {
        String answer = npcRagService.chat(memberId, question);
        return ApiResponse.ok(new NpcChatResponse(answer));
    }
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew build -x test
```

기대 결과: `BUILD SUCCESSFUL`

- [ ] **Step 4: 수동 WebSocket 동작 확인 (선택)**

서버 시작 후 Postman 또는 wscat으로 아래 순서 검증:
1. `GET /api/v1/npc/chat?memberId=1&question=스택이 뭐야?` → 동기 응답 확인
2. WebSocket 연결: `ws://localhost:8080/ws`
3. STOMP SUBSCRIBE: `/topic/npc/1`
4. STOMP SEND: `/app/npc/chat` with `{"memberId":1,"question":"큐가 뭐야?"}`
5. 토큰들이 실시간으로 수신되고 마지막에 `[DONE]` 수신 확인

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/studycafe/domain/npc/controller/
git commit -m "feat: NPC WebSocket STOMP 연동 (스트리밍 응답)"
```

---

### Task 6: 세션 기록 임베딩 저장

> **이 태스크에서 배우는 것:**
>
> 세션 피드백 RAG의 전제 조건은 **과거 세션이 VectorStore에 쌓여 있어야** 한다는 것이다.
> 세션을 자연어 텍스트로 변환 후 임베딩하여 저장하면,
> 나중에 "저녁 9시 CS 공부 달성" 같은 쿼리로 유사한 과거 세션을 검색할 수 있다.
>
> **자연어 변환이 중요한 이유**: 임베딩 모델은 텍스트의 의미를 벡터화한다.
> `"2026-07-06 21:00, CS, 120min, true"` 보다
> `"2026년 7월 6일 저녁 9시, CS 공부, 2시간, 목표 달성"` 이 의미 검색에 훨씬 유리하다.

**Files:**
- Create: `src/main/java/com/studycafe/domain/session/entity/StudySession.java`
- Create: `src/main/java/com/studycafe/domain/session/repository/StudySessionRepository.java`
- Create: `src/main/java/com/studycafe/domain/session/dto/SessionCompleteRequest.java`
- Create: `src/main/java/com/studycafe/domain/session/service/SessionEmbeddingService.java`
- Test: `src/test/java/com/studycafe/domain/session/service/SessionEmbeddingServiceTest.java`

**Interfaces:**
- Consumes: `VectorStore` 빈, `StudySessionRepository`
- Produces:
  - `SessionEmbeddingService.saveSessionEmbedding(memberId, subject, durationMinutes, achieved, startedAt)` — 세션 → VectorStore 저장
  - `StudySession` 엔티티 + Repository

- [ ] **Step 1: StudySession 엔티티 작성**

```java
// src/main/java/com/studycafe/domain/session/entity/StudySession.java
package com.studycafe.domain.session.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "study_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class StudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private String subject;        // 공부 과목/주제

    @Column(nullable = false)
    private Integer durationMinutes;  // 목표 시간 (분)

    @Column(nullable = false)
    private Boolean achieved;      // 달성 여부

    @Column(nullable = false)
    private LocalDateTime startedAt;  // 세션 시작 시각

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public static StudySession of(Long memberId, String subject,
                                   int durationMinutes, boolean achieved,
                                   LocalDateTime startedAt) {
        StudySession session = new StudySession();
        session.memberId = memberId;
        session.subject = subject;
        session.durationMinutes = durationMinutes;
        session.achieved = achieved;
        session.startedAt = startedAt;
        return session;
    }
}
```

- [ ] **Step 2: StudySessionRepository 작성**

```java
// src/main/java/com/studycafe/domain/session/repository/StudySessionRepository.java
package com.studycafe.domain.session.repository;

import com.studycafe.domain.session.entity.StudySession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {
}
```

- [ ] **Step 3: SessionCompleteRequest DTO 작성**

```java
// src/main/java/com/studycafe/domain/session/dto/SessionCompleteRequest.java
package com.studycafe.domain.session.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class SessionCompleteRequest {
    private Long memberId;
    private String subject;
    private Integer durationMinutes;
    private Boolean achieved;
    private LocalDateTime startedAt;
}
```

- [ ] **Step 4: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/session/service/SessionEmbeddingServiceTest.java
package com.studycafe.domain.session.service;

import com.studycafe.domain.session.repository.StudySessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionEmbeddingServiceTest {

    @InjectMocks
    private SessionEmbeddingService sessionEmbeddingService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private StudySessionRepository studySessionRepository;

    @Test
    @DisplayName("세션 완료 - type=session, userId 메타데이터로 VectorStore에 저장된다")
    void complete_storesEmbeddingWithSessionMetadata() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 6, 21, 0);

        sessionEmbeddingService.saveSessionEmbedding(1L, "CS", 120, true, startedAt);

        // 세션 기록 DB 저장 확인
        verify(studySessionRepository).save(any());

        // VectorStore에 올바른 메타데이터로 저장 확인
        verify(vectorStore).add(argThat((List<Document> docs) ->
                docs.size() == 1 &&
                "session".equals(docs.get(0).getMetadata().get("type")) &&
                "1".equals(docs.get(0).getMetadata().get("userId"))
        ));
    }
}
```

- [ ] **Step 5: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.session.service.SessionEmbeddingServiceTest"
```

기대 결과: FAIL — `SessionEmbeddingService` 클래스 없음

- [ ] **Step 6: SessionEmbeddingService 구현**

```java
// src/main/java/com/studycafe/domain/session/service/SessionEmbeddingService.java
package com.studycafe.domain.session.service;

import com.studycafe.domain.session.entity.StudySession;
import com.studycafe.domain.session.repository.StudySessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SessionEmbeddingService {

    private final VectorStore vectorStore;
    private final StudySessionRepository studySessionRepository;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH시 mm분");

    @Transactional
    public void saveSessionEmbedding(Long memberId, String subject,
                                      int durationMinutes, boolean achieved,
                                      LocalDateTime startedAt) {
        // 1. RDB에 세션 기록 저장
        studySessionRepository.save(StudySession.of(memberId, subject, durationMinutes, achieved, startedAt));

        // 2. 자연어 텍스트로 변환 — 의미 검색 정확도를 높이기 위해 자연스러운 문장 사용
        String text = buildSessionText(subject, durationMinutes, achieved, startedAt);

        // 3. VectorStore에 임베딩 저장
        Document doc = new Document(text, Map.of(
                "type", "session",
                "userId", String.valueOf(memberId),
                "achieved", String.valueOf(achieved)
        ));
        vectorStore.add(List.of(doc));
    }

    private String buildSessionText(String subject, int durationMinutes,
                                     boolean achieved, LocalDateTime startedAt) {
        String timeStr = startedAt.format(FORMATTER);
        int hours = durationMinutes / 60;
        int minutes = durationMinutes % 60;
        String durationStr = hours > 0
                ? hours + "시간" + (minutes > 0 ? " " + minutes + "분" : "")
                : minutes + "분";
        String achievedStr = achieved ? "목표 달성" : "목표 미달성";

        return String.format("%s, %s 공부, %s, %s", timeStr, subject, durationStr, achievedStr);
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.session.service.SessionEmbeddingServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 1 test passed

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/studycafe/domain/session/ src/test/java/com/studycafe/domain/session/
git commit -m "feat: 세션 완료 기록 → VectorStore 임베딩 저장 (세션 피드백 RAG 전처리)"
```

---

### Task 7: 세션 피드백 RAG

> **이 태스크에서 배우는 것:**
>
> 세션 피드백은 QuestionAnswerAdvisor 대신 **직접 검색 + 프롬프트 조립** 방식을 사용한다.
> 이유: 피드백은 "질문-답변" 형태가 아니라 과거 패턴 분석 + 격려/제안이기 때문이다.
>
> **VectorStore.similaritySearch()**: 쿼리 텍스트와 가장 유사한 문서들을 반환한다.
> 이번 세션 텍스트를 쿼리로 사용하면 비슷한 시간대, 비슷한 과목의 과거 세션을 찾는다.
>
> **PromptTemplate**: 프롬프트에 변수를 주입하는 Spring AI 유틸리티.
> 검색된 과거 세션 목록을 `{history}` 자리에 넣어 LLM에게 맥락을 제공한다.

**Files:**
- Create: `src/main/java/com/studycafe/domain/session/service/SessionFeedbackService.java`
- Create: `src/main/java/com/studycafe/domain/session/controller/SessionController.java`
- Test: `src/test/java/com/studycafe/domain/session/service/SessionFeedbackServiceTest.java`

**Interfaces:**
- Consumes: `VectorStore` 빈, `ChatClient` 빈, `SessionEmbeddingService`
- Produces:
  - `SessionFeedbackService.generateFeedback(memberId, subject, durationMinutes, achieved, startedAt): String`
  - `POST /api/v1/sessions/complete` — 세션 완료 + 피드백 반환 엔드포인트

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// src/test/java/com/studycafe/domain/session/service/SessionFeedbackServiceTest.java
package com.studycafe.domain.session.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionFeedbackServiceTest {

    @InjectMocks
    private SessionFeedbackService sessionFeedbackService;

    @Mock
    private ChatClient chatClient;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private SessionEmbeddingService sessionEmbeddingService;

    @Test
    @DisplayName("세션 피드백 - 과거 기록 검색 후 LLM 피드백을 반환한다")
    void generateFeedback_returnsPersonalizedFeedback() {
        // 과거 유사 세션 2개를 Mock으로 반환
        List<Document> pastSessions = List.of(
                new Document("2026년 7월 5일 21시, CS 공부, 2시간, 목표 달성",
                        Map.of("type", "session", "userId", "1")),
                new Document("2026년 7월 4일 21시, CS 공부, 2시간, 목표 미달성",
                        Map.of("type", "session", "userId", "1"))
        );
        given(vectorStore.similaritySearch(any())).willReturn(pastSessions);

        // ChatClient 체이닝 Mock
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);
        given(callResponseSpec.content()).willReturn("저녁 9시 CS 공부 달성률이 높아요! 오늘도 잘 하셨어요.");

        String feedback = sessionFeedbackService.generateFeedback(
                1L, "CS", 120, true, LocalDateTime.of(2026, 7, 6, 21, 0)
        );

        assertThat(feedback).contains("저녁 9시");
        verify(sessionEmbeddingService).saveSessionEmbedding(any(), any(), anyInt(), anyBoolean(), any());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.studycafe.domain.session.service.SessionFeedbackServiceTest"
```

기대 결과: FAIL — `SessionFeedbackService` 클래스 없음

- [ ] **Step 3: SessionFeedbackService 구현**

```java
// src/main/java/com/studycafe/domain/session/service/SessionFeedbackService.java
package com.studycafe.domain.session.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionFeedbackService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SessionEmbeddingService sessionEmbeddingService;

    public String generateFeedback(Long memberId, String subject,
                                    int durationMinutes, boolean achieved,
                                    LocalDateTime startedAt) {
        // 1. 세션 기록 저장 + 임베딩
        sessionEmbeddingService.saveSessionEmbedding(memberId, subject, durationMinutes, achieved, startedAt);

        // 2. 유사한 과거 세션 검색
        List<Document> pastSessions = searchPastSessions(memberId, subject, startedAt);

        // 3. 과거 패턴 + 이번 세션을 프롬프트에 담아 LLM 피드백 요청
        String prompt = buildFeedbackPrompt(subject, durationMinutes, achieved, startedAt, pastSessions);
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    private List<Document> searchPastSessions(Long memberId, String subject, LocalDateTime startedAt) {
        // 이번 세션과 비슷한 시간대, 비슷한 과목의 과거 세션을 검색
        String queryText = startedAt.getHour() + "시 " + subject + " 공부";
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.eq("type", "session"),
                b.eq("userId", String.valueOf(memberId))
        ).build();

        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(queryText)
                .topK(3)
                .filterExpression(filter)
                .build());
    }

    private String buildFeedbackPrompt(String subject, int durationMinutes,
                                        boolean achieved, LocalDateTime startedAt,
                                        List<Document> pastSessions) {
        String achievedStr = achieved ? "달성" : "미달성";
        int hours = durationMinutes / 60;

        String historyText = pastSessions.isEmpty()
                ? "과거 기록 없음 (첫 세션)"
                : pastSessions.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n- ", "- ", ""));

        return String.format("""
                너는 공부 코치 NPC야. 사용자의 오늘 세션과 과거 패턴을 보고 격려와 실질적인 조언을 해줘.
                답변은 2-3문장으로 짧고 친근하게. 이모지 사용 가능.
                
                [오늘 세션]
                - 과목: %s
                - 목표 시간: %d시간
                - 달성 여부: %s
                - 시작 시각: %d시
                
                [과거 유사 세션]
                %s
                """, subject, hours, achievedStr, startedAt.getHour(), historyText);
    }
}
```

- [ ] **Step 4: SessionController 구현**

```java
// src/main/java/com/studycafe/domain/session/controller/SessionController.java
package com.studycafe.domain.session.controller;

import com.studycafe.domain.session.dto.SessionCompleteRequest;
import com.studycafe.domain.session.service.SessionFeedbackService;
import com.studycafe.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionFeedbackService sessionFeedbackService;

    // 세션 완료 → 피드백 생성 → 반환 (포인트 적립은 PointService 연동 시 추가)
    @PostMapping("/complete")
    public ApiResponse<Map<String, String>> complete(@RequestBody SessionCompleteRequest request) {
        String feedback = sessionFeedbackService.generateFeedback(
                request.getMemberId(),
                request.getSubject(),
                request.getDurationMinutes(),
                request.getAchieved(),
                request.getStartedAt()
        );
        return ApiResponse.ok(Map.of("feedback", feedback));
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.studycafe.domain.session.service.SessionFeedbackServiceTest"
```

기대 결과: `BUILD SUCCESSFUL`, 1 test passed

- [ ] **Step 6: 전체 테스트 확인**

```bash
./gradlew test
```

기대 결과: 전체 테스트 통과

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/studycafe/domain/session/service/SessionFeedbackService.java \
        src/main/java/com/studycafe/domain/session/controller/SessionController.java \
        src/test/java/com/studycafe/domain/session/service/SessionFeedbackServiceTest.java
git commit -m "feat: 세션 피드백 RAG 구현 (과거 세션 유사도 검색 + LLM 개인화 피드백)"
```

---

## 부록: 로컬 테스트 체크리스트

실제 OpenAI API를 호출하는 통합 테스트 전에 아래를 확인한다.

```bash
# 1. Google AI Studio에서 API 키 발급 후 환경변수 설정
# 발급 경로: https://aistudio.google.com → Get API key
export GOOGLE_AI_API_KEY=AIza...

# 2. PostgreSQL + PGVector 실행 (Docker)
docker run -d \
  --name studycafe-pg \
  -e POSTGRES_DB=studycafe \
  -e POSTGRES_USER=studycafe \
  -e POSTGRES_PASSWORD=studycafe \
  -p 5432:5432 \
  pgvector/pgvector:pg17

# 3. 서버 시작 (첫 실행 시 vector_store 테이블 자동 생성)
./gradlew bootRun

# 4. 글로벌 문서 적재 확인 (서버 로그에서)
# INFO ... 글로벌 지식 적재: N 청크 → VectorStore

# 5. NPC 채팅 테스트
curl "http://localhost:8080/api/v1/npc/chat?memberId=1&question=스택이 뭐야?"

# 6. PDF 업로드 테스트
curl -X POST "http://localhost:8080/api/v1/documents/upload" \
  -F "memberId=1" \
  -F "file=@/path/to/your.pdf"

# 7. 세션 완료 + 피드백 테스트
curl -X POST "http://localhost:8080/api/v1/sessions/complete" \
  -H "Content-Type: application/json" \
  -d '{"memberId":1,"subject":"CS","durationMinutes":120,"achieved":true,"startedAt":"2026-07-06T21:00:00"}'
```
