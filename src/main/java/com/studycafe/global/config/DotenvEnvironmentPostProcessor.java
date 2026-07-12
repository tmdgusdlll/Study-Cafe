package com.studycafe.global.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * 프로젝트 루트의 .env 파일을 읽어 스프링 환경에 주입한다.
 * Spring Boot 4는 EnvironmentPostProcessor를 META-INF/spring.factories에 등록하며,
 * spring-dotenv(구 방식)는 Boot 4와 호환되지 않아 dotenv-java로 직접 로드한다.
 * .env가 없으면 무시하며(운영에서는 실제 OS 환경변수 사용), OS 환경변수가 .env보다 우선한다.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        // .env 파일에 실제로 선언된 키만 주입한다(시스템 환경변수는 제외).
        Map<String, Object> props = new HashMap<>();
        for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
            props.put(entry.getKey(), entry.getValue());
        }

        if (!props.isEmpty()) {
            // OS 환경변수 바로 다음 순위로 등록 → 실제 환경변수가 .env를 덮어쓴다.
            environment.getPropertySources().addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource("dotenv", props));
        }
    }
}
