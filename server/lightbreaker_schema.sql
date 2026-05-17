CREATE DATABASE IF NOT EXISTS `LightBreaker`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `LightBreaker`;

CREATE TABLE IF NOT EXISTS app_profiles (
    install_id VARCHAR(64) PRIMARY KEY,
    app_name VARCHAR(64) NOT NULL DEFAULT 'LightBreaker',
    xp INT UNSIGNED NOT NULL DEFAULT 0,
    level_no INT UNSIGNED NOT NULL DEFAULT 1,
    last_left_device VARCHAR(128) NULL,
    last_right_device VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS glove_devices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL,
    hand ENUM('left', 'right', 'unknown') NOT NULL DEFAULT 'unknown',
    bluetooth_name VARCHAR(128) NOT NULL,
    bluetooth_address_hash VARCHAR(128) NULL,
    last_battery TINYINT UNSIGNED NULL,
    last_packet_no TINYINT UNSIGNED NULL,
    last_gyro_count TINYINT UNSIGNED NULL,
    last_gyro_power TINYINT UNSIGNED NULL,
    last_seen_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_glove_device (install_id, hand, bluetooth_name),
    KEY idx_glove_install_seen (install_id, last_seen_at),
    CONSTRAINT fk_glove_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS gallery_items (
    id VARCHAR(96) NOT NULL PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    theme VARCHAR(128) NOT NULL,
    prompt TEXT NULL,
    seed BIGINT NULL,
    finished_at_ms BIGINT NOT NULL,
    total_hits INT UNSIGNED NOT NULL DEFAULT 0,
    opened_tiles INT UNSIGNED NOT NULL DEFAULT 0,
    total_tiles INT UNSIGNED NOT NULL DEFAULT 160,
    max_combo INT UNSIGNED NOT NULL DEFAULT 0,
    calories DECIMAL(8,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_gallery_install_finished (install_id, finished_at_ms),
    CONSTRAINT fk_gallery_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS session_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    local_id BIGINT NULL,
    install_id VARCHAR(64) NOT NULL,
    gallery_item_id VARCHAR(96) NULL,
    title VARCHAR(128) NOT NULL,
    started_at_ms BIGINT NOT NULL,
    ended_at_ms BIGINT NOT NULL,
    duration_seconds INT UNSIGNED NOT NULL DEFAULT 0,
    total_hits INT UNSIGNED NOT NULL DEFAULT 0,
    left_hits INT UNSIGNED NOT NULL DEFAULT 0,
    right_hits INT UNSIGNED NOT NULL DEFAULT 0,
    opened_tiles INT UNSIGNED NOT NULL DEFAULT 0,
    total_tiles INT UNSIGNED NOT NULL DEFAULT 160,
    max_combo INT UNSIGNED NOT NULL DEFAULT 0,
    calories DECIMAL(8,2) NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_session_install_ended (install_id, ended_at_ms),
    KEY idx_session_gallery (gallery_item_id),
    CONSTRAINT fk_session_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE,
    CONSTRAINT fk_session_gallery FOREIGN KEY (gallery_item_id) REFERENCES gallery_items(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS achievement_states (
    install_id VARCHAR(64) NOT NULL,
    achievement_key VARCHAR(96) NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(255) NULL,
    unlocked BOOLEAN NOT NULL DEFAULT FALSE,
    progress INT UNSIGNED NOT NULL DEFAULT 0,
    target INT UNSIGNED NOT NULL DEFAULT 1,
    unlocked_at_ms BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (install_id, achievement_key),
    CONSTRAINT fk_achievement_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS glove_packet_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL,
    hand ENUM('left', 'right', 'unknown') NOT NULL DEFAULT 'unknown',
    packet_no TINYINT UNSIGNED NOT NULL,
    battery TINYINT UNSIGNED NOT NULL,
    gyro_punch_count TINYINT UNSIGNED NOT NULL,
    pressure_punch_count TINYINT UNSIGNED NULL,
    gyro_power TINYINT UNSIGNED NOT NULL,
    pressure_power TINYINT UNSIGNED NULL,
    raw_hex VARCHAR(32) NOT NULL,
    received_at_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_packet_install_time (install_id, received_at_ms),
    KEY idx_packet_hand_time (hand, received_at_ms),
    CONSTRAINT fk_packet_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sync_install_created (install_id, created_at),
    KEY idx_sync_type_created (event_type, created_at),
    CONSTRAINT fk_sync_profile FOREIGN KEY (install_id) REFERENCES app_profiles(install_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
