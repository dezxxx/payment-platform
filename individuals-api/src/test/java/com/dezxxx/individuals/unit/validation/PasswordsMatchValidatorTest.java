package com.dezxxx.individuals.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dezxxx.individuals.api.model.RegistrationRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the rule through a real validator rather than calling
 * {@code isValid} directly.
 *
 * <p>Two things break silently and neither is visible from the method itself:
 * the annotation could stop reaching the generated class, and the violation
 * could end up on the object instead of on a field, which is what decides
 * whether the client is told which field was wrong. Both are exercised only by
 * validating a whole request.
 */
@DisplayName("PasswordsMatch")
class PasswordsMatchValidatorTest {

    private static final String PASSWORD = "Str0ngP@ssw0rd";

    private static final String MISMATCH_MESSAGE = "must match password";

    private static ValidatorFactory factory;

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("accepts a request whose confirmation repeats the password")
    void acceptsMatchingConfirmation() {
        Set<ConstraintViolation<RegistrationRequest>> violations = validator.validate(request(PASSWORD, PASSWORD));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("rejects a mismatch and reports it on confirmPassword")
    void reportsMismatchOnTheConfirmationField() {
        Set<ConstraintViolation<RegistrationRequest>> violations =
                validator.validate(request(PASSWORD, "Str0ngP@ssw0rt"));

        assertThat(violations).hasSize(1);
        ConstraintViolation<RegistrationRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("confirmPassword");
        assertThat(violation.getMessage()).isEqualTo(MISMATCH_MESSAGE);
    }

    /**
     * The omission is already reported by {@code @NotNull}; this rule must stay
     * quiet so one mistake does not produce two lines in the answer.
     */
    @Test
    @DisplayName("stays silent when a value is missing, leaving that to @NotNull")
    void ignoresMissingValues() {
        Set<ConstraintViolation<RegistrationRequest>> violations = validator.validate(request(PASSWORD, null));

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .doesNotContain(MISMATCH_MESSAGE);
    }

    private static RegistrationRequest request(String password, String confirmPassword) {
        return new RegistrationRequest()
                .email("user@dezxxx.com")
                .password(password)
                .confirmPassword(confirmPassword)
                .firstName("Ivan")
                .lastName("Ivanov");
    }
}
