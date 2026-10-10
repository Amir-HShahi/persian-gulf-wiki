package com.persiangulfwiki.core.marinedata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

// Equality over both columns is the point of a composite id, so the Lombok equals here is the
// business key, not the full-field-set footgun the entity rule warns about.
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MarineSourceSyncId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "source_key", nullable = false, length = 32)
    private String sourceKey;

    @Column(name = "station_key", nullable = false, length = 64)
    private String stationKey;
}
