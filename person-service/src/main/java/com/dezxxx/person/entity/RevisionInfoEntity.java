package com.dezxxx.person.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

// One Envers revision = one transaction that changed audited data.
// Our own entity, so person_history.revinfo is defined by V005, not by Hibernate defaults.
@Getter
@Setter
@Entity
@RevisionEntity
@Table(name = "revinfo", schema = "person_history")
public class RevisionInfoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private Long id;

    // epoch milliseconds, set by Envers when the revision is written
    @RevisionTimestamp
    @Column(name = "revtstmp")
    private long timestamp;
}
