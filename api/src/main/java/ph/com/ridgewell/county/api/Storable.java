package ph.com.ridgewell.county.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Free text PostgreSQL can store: no NUL character. A null value is valid, as for every constraint
 * here; combine with {@code @NotBlank} or {@code @Size} for the rest.
 */
@Documented
@Constraint(validatedBy = Storable.Validator.class)
@Target({
  ElementType.METHOD,
  ElementType.FIELD,
  ElementType.ANNOTATION_TYPE,
  ElementType.CONSTRUCTOR,
  ElementType.PARAMETER,
  ElementType.TYPE_USE,
  ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Storable {
  String message() default "Remove characters that cannot be stored";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /**
   * A plain check rather than a {@code @Pattern}, so the generated contract keeps the pattern that
   * {@code @NotBlank} documents on the same field.
   */
  class Validator implements ConstraintValidator<Storable, CharSequence> {
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
      return value == null || value.chars().noneMatch(c -> c == 0);
    }
  }
}
