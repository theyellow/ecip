package io.emcip.conversation.context.config;

import java.io.IOException;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * GraalVM native image hints for emcip-conversation-context.
 *
 * <p>Spring Boot AOT handles entity reflection, JPA repository proxies, and Kafka listener wiring.
 * We only need to register resources that Spring Boot's auto-configuration would normally register
 * but cannot because spring.liquibase.enabled=false (our custom LiquibaseConfig is used instead).
 *
 * <p>Hibernate 7 uses Class.forName() and reflective instantiation extensively at runtime for
 * dialect types, JBoss Logging implementations, and strategy classes. We register all org.hibernate
 * classes for full reflection access. Array types and event listeners are handled by
 * hibernate-graalvm's GraalVMStaticFeature (configured in the native Maven profile).
 */
public class ConversationContextRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("db/changelog/**");
        registerAllHibernateClasses(hints, classLoader);
    }

    private void registerAllHibernateClasses(RuntimeHints hints, ClassLoader classLoader) {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(classLoader);
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        try {
            for (var resource : resolver.getResources("classpath*:org/hibernate/**/*.class")) {
                var className =
                        factory.getMetadataReader(resource).getClassMetadata().getClassName();
                if (className.endsWith("package-info")) {
                    continue;
                }
                hints.reflection()
                        .registerType(
                                TypeReference.of(className),
                                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                                MemberCategory.INVOKE_DECLARED_METHODS,
                                MemberCategory.DECLARED_FIELDS);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan Hibernate classes", e);
        }
    }
}
