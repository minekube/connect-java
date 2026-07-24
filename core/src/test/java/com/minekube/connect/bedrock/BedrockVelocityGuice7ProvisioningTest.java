package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.util.Metrics;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the Velocity 4.0.0 plugin-provisioning failure ("Cant create plugin
 * connect").
 *
 * <p><b>Root cause.</b> The Connect Velocity plugin does not shade Guice — {@code
 * velocity/build.gradle.kts} marks it {@code provided} — and {@code VelocityPlugin} builds a
 * <em>child</em> of Velocity's own injector, so the Connect graph is provisioned by whatever Guice
 * the platform ships. Velocity 4.0.0 (a new major) ships Guice 7, which dropped {@code javax.inject}
 * support and recognizes only {@code com.google.inject} and {@code jakarta.inject} annotations.
 * {@link BedrockIdentityKeyProvider} and {@link BedrockAdmissionCoordinator} were annotated with
 * {@code javax.inject.Inject}/{@code @Singleton}, so under Guice 7 they had no discoverable
 * {@code @Inject} constructor and no no-arg constructor and could not be provisioned as the
 * {@code BedrockIdentityKeyProvider} parameter of {@code CommonModule.bedrockIdentityReadiness(...)}
 * — failing the whole {@code VelocityPlugin} injector. Spigot/Bungee shade their own Guice 6 and
 * Velocity 3.x ships Guice 5; both still recognize {@code javax.inject}, which is why only Velocity
 * 4.0.0 broke. The javax dependency was introduced in #59 (which replaced an explicit
 * {@code @Provides} factory with a just-in-time {@code javax.inject.Inject} constructor), not #61.
 *
 * <p><b>Test limitation.</b> This repo compiles and tests against Guice 6 (see
 * {@code Versions.guiceVersion}), which recognizes BOTH {@code javax.inject} and {@code
 * com.google.inject}. Provisioning the graph through a real Guice-6 injector therefore succeeds even
 * with the javax annotations and cannot reproduce the failure. No Guice 7 / Velocity 4.0.0 harness
 * is on the classpath, so these tests instead replicate Guice 7's exact injectable-constructor
 * discovery rule and forbid {@code javax.inject} annotations on the Connect DI classes that sit on
 * the Velocity plugin graph. They fail before the fix and pass after it.
 */
class BedrockVelocityGuice7ProvisioningTest {

    /**
     * Annotation types Guice 7 accepts as an injectable-constructor marker. Referenced by
     * fully-qualified name so this test needs neither Guice 7 nor {@code jakarta.inject} on its
     * classpath.
     */
    private static final Set<String> GUICE7_INJECT_ANNOTATIONS =
            Set.of("com.google.inject.Inject", "jakarta.inject.Inject");

    private static final String JAVAX_INJECT_PREFIX = "javax.inject.";

    /**
     * The reported failure: {@code BedrockIdentityKeyProvider} is the 2nd parameter of
     * {@code CommonModule.bedrockIdentityReadiness} and could not be created under Guice 7. It has
     * no no-arg constructor, so Guice 7 must be able to find its {@code @Inject} constructor.
     */
    @Test
    void bedrockIdentityKeyProviderIsProvisionableUnderGuice7() {
        assertGuice7CanProvision(BedrockIdentityKeyProvider.class);
    }

    /**
     * {@code BedrockAdmissionCoordinator} is injected into {@code ConnectPlatform} (and thus the
     * Velocity graph) and, like the key provider, has no no-arg constructor. It carried the same
     * {@code javax.inject} annotations and would fail Guice 7 provisioning next.
     */
    @Test
    void bedrockAdmissionCoordinatorIsProvisionableUnderGuice7() {
        assertGuice7CanProvision(BedrockAdmissionCoordinator.class);
    }

    /**
     * Broader guard covering scope/qualifier annotations too: a {@code javax.inject} {@code
     * @Singleton} scope or {@code @Named} qualifier is invisible to Guice 7, so a singleton would
     * silently become per-request and a qualified binding would fail to resolve. None of the
     * Connect DI classes on the Velocity plugin graph may use {@code javax.inject}; the whole rest
     * of the codebase already uses {@code com.google.inject}.
     */
    @Test
    void connectDiClassesCarryNoJavaxInjectAnnotations() {
        for (Class<?> type : List.of(
                BedrockIdentityKeyProvider.class,
                BedrockAdmissionCoordinator.class,
                BedrockIdentityEnforcer.class,
                VerifiedBedrockIdentityRegistry.class,
                Metrics.class)) {
            List<String> offenders = javaxInjectAnnotations(type);
            assertTrue(offenders.isEmpty(),
                    type.getName() + " must not use javax.inject annotations (invisible to Guice 7 "
                            + "on Velocity 4.0.0): " + offenders);
        }
    }

    /**
     * Positive graph check: {@code BedrockIdentityKeyProvider} and {@code BedrockAdmissionCoordinator}
     * still resolve through a real Guice injector wired the way {@code CommonModule} plus the
     * parent-injector pattern do it. Green under Guice 6 both before and after the fix; documents
     * the wiring and guards against unrelated graph breakage.
     */
    @Test
    void bedrockGraphResolvesThroughRealGuiceInjector() {
        Injector parent = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ConfigHolder.class).toInstance(new ConfigHolder());
                bind(ConnectLogger.class).toInstance(mock(ConnectLogger.class));
                bind(OkHttpClient.class)
                        .annotatedWith(Names.named("defaultHttpClient"))
                        .toInstance(new OkHttpClient());
            }
        });

        assertNotNull(parent.getInstance(BedrockIdentityKeyProvider.class));
        assertNotNull(parent.getInstance(BedrockAdmissionCoordinator.class));
    }

    // --- Guice 7 injectable-constructor discovery replica -------------------------------------

    /**
     * Replicates Guice 7's {@code InjectionPoint.forConstructorOf} rule: a type is provisionable
     * only if it has exactly one constructor annotated with an inject annotation Guice 7 recognizes,
     * or (failing that) an injectable no-arg constructor. Under {@code javax.inject} annotations
     * Guice 7 sees neither, producing the "no @Inject constructor and no no-arg constructor"
     * failure.
     */
    private static void assertGuice7CanProvision(Class<?> type) {
        List<Constructor<?>> injectConstructors = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (hasGuice7InjectAnnotation(constructor)) {
                injectConstructors.add(constructor);
            }
        }

        assertTrue(injectConstructors.size() <= 1,
                type.getName() + " has more than one Guice 7 @Inject constructor: "
                        + injectConstructors);

        boolean provisionable =
                injectConstructors.size() == 1 || hasInjectableNoArgConstructor(type);
        assertTrue(provisionable,
                type.getName() + " has NO @Inject-annotated constructor that Guice 7 recognizes "
                        + "(it uses only javax.inject, which Guice 7 on Velocity 4.0.0 ignores) and "
                        + "NO no-arg constructor, so Guice 7 cannot provision it — reproducing the "
                        + "\"Cant create plugin connect\" failure.");
    }

    private static boolean hasGuice7InjectAnnotation(Constructor<?> constructor) {
        for (Annotation annotation : constructor.getAnnotations()) {
            if (GUICE7_INJECT_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInjectableNoArgConstructor(Class<?> type) {
        try {
            Constructor<?> noArg = type.getDeclaredConstructor();
            return !Modifier.isPrivate(noArg.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // --- javax.inject annotation scan ----------------------------------------------------------

    private static List<String> javaxInjectAnnotations(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        collectJavaxInject(type, offenders); // class-level scope, e.g. @Singleton
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            collectJavaxInject(constructor, offenders);
            for (Parameter parameter : constructor.getParameters()) {
                collectJavaxInject(parameter, offenders);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            collectJavaxInject(field, offenders);
        }
        for (Method method : type.getDeclaredMethods()) {
            collectJavaxInject(method, offenders);
            for (Parameter parameter : method.getParameters()) {
                collectJavaxInject(parameter, offenders);
            }
        }
        return offenders;
    }

    private static void collectJavaxInject(AnnotatedElement element, List<String> offenders) {
        for (Annotation annotation : element.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith(JAVAX_INJECT_PREFIX)) {
                offenders.add(name + " on " + element);
            }
        }
    }
}
