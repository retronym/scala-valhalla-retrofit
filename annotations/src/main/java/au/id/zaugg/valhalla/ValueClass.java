package au.id.zaugg.valhalla;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt in to translating this <strong>final class of final fields</strong> into a
 * JEP 401 {@code value class} at load/build time (the retrofit agent's Phase 2).
 *
 * <p>The canonical target is a Scala {@code case class} whose fields are all
 * immutable and whose superclass is {@code Object} (i.e. it does not extend a
 * stateful identity class). On an annotated class the agent will, if it is
 * eligible:
 * <ul>
 *   <li>clear {@code ACC_IDENTITY} so the class becomes a value class;</li>
 *   <li>mark every instance field strict and final;</li>
 *   <li>stamp the preview classfile version.</li>
 * </ul>
 *
 * <p>This is an <em>opt-in</em> marker rather than something the agent infers,
 * because promotion is not semantically free: a value class has
 * <strong>no identity</strong>. After translation, {@code ==}/acmp on instances
 * compares by field state (so two distinct allocations with equal fields are
 * equal), {@code synchronized}/identity-hash are unavailable, and reference
 * identity ({@code eq}) collapses into state equality. Only annotate types for
 * which losing identity is sound.
 *
 * <p>The annotation is retained at {@link RetentionPolicy#RUNTIME} so the agent
 * can read it from the classfile's {@code RuntimeVisibleAnnotations}; it adds no
 * runtime behaviour and the {@code valhalla-annotations} jar has no
 * dependencies.
 *
 * <p>If the class is not eligible (a non-{@code Object} superclass, a non-final
 * or post-{@code super()} field, or — unless {@link #allowFloating()} — a
 * {@code float}/{@code double} field), the agent leaves it unchanged rather than
 * producing something that fails verification.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ValueClass {

    /**
     * Permit {@code float}/{@code double} fields. Off by default because value
     * {@code ==}/acmp uses <em>bitwise</em> substitutability, which inverts both
     * floating-point edge cases relative to Scala/Java numeric {@code ==}:
     * acmp reports {@code NaN == NaN} as {@code true} and {@code +0.0 == -0.0}
     * as {@code false}. Set to {@code true} only if that change is acceptable
     * for this type.
     */
    boolean allowFloating() default false;
}
