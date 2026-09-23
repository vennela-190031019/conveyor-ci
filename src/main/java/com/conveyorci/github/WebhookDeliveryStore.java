package com.conveyorci.github;

import java.sql.Types;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Remembers processed webhook deliveries so GitHub's redeliveries don't start duplicate runs. */
@Repository
public class WebhookDeliveryStore {

    private final NamedParameterJdbcTemplate jdbc;

    public WebhookDeliveryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String deliveryId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE id = :id",
                Map.of("id", deliveryId), Integer.class);
        return count != null && count > 0;
    }

    /** Returns false if this delivery was already recorded (the primary key rejects it). */
    public boolean record(String deliveryId, String event, String outcome, Long runId) {
        return jdbc.update("""
                INSERT INTO webhook_delivery (id, event, outcome, run_id)
                VALUES (:id, :event, :outcome, :run)
                ON CONFLICT (id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", deliveryId)
                .addValue("event", event)
                .addValue("outcome", outcome.length() > 255 ? outcome.substring(0, 252) + "..." : outcome)
                .addValue("run", runId, Types.BIGINT)) == 1;
    }
}
