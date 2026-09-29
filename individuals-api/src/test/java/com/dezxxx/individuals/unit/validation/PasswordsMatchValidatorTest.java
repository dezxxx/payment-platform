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

// Through a real validator, not isValid() directly: checks the annotation
// reaches the generated class and the error lands on a field
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
    @DisplayName("UT-REG-002: rejects a mismatch and reports it on confirmPassword")
    void reportsMismatchOnTheConfirmationField() {
        Set<ConstraintViolation<RegistrationRequest>> violations =
                validator.validate(request(PASSWORD, "Str0ngP@ssw0rt"));

        assertThat(violations).hasSize(1);
        ConstraintViolation<RegistrationRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath()).hasToString("confirmPassword");
        assertThat(violation.getMessage()).isEqualTo(MISMATCH_MESSAGE);
    }

    // @NotNull already reports it - one mistake, one line
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
