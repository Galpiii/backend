package com.github.galpiii.galpi.domain.user.repository;

import com.github.galpiii.galpi.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
