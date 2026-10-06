package com.dezxxx.person.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

// Root of the aggregate: address and individual are saved and deleted through it
@Getter
@Setter
@Audited(withModifiedFlag = true)
@Entity
@Table(name = "users", schema = "person")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // a secret is not copied into the history tables
    @NotAudited
    private String secretKey;

    private String email;

    @CreationTimestamp
    private Instant created;

    @UpdateTimestamp
    private Instant updated;

    @Version
    private Long version;

    private String firstName;

    private String lastName;

    private boolean filled;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "address_id")
    private AddressEntity address;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private IndividualEntity individual;

    // individuals.user_id is written from IndividualEntity.user, so both sides are set together
    public void setIndividual(IndividualEntity individual) {
        this.individual = individual;
        if (individual != null) {
            individual.setUser(this);
        }
    }
}
