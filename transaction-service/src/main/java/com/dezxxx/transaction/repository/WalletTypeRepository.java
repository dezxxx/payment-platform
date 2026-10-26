package com.dezxxx.transaction.repository;

import com.dezxxx.transaction.entity.wallet.WalletTypeEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTypeRepository extends JpaRepository<WalletTypeEntity, UUID> {
}
