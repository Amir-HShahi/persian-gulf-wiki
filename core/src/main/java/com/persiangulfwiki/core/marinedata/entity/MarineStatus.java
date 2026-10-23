package com.persiangulfwiki.core.marinedata.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = MarineStatus.VALUES_DOC)
public enum MarineStatus {
    NORMAL,
    WATCH,
    WARNING,
    ALERT;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `NORMAL`: no concern: bleaching alert level 0, air quality index up to 40, or a temperature anomaly \
            under +1 °C.
            - `WATCH`: early sign: bleaching alert level 1, or air quality index 40-60.
            - `WARNING`: bleaching alert level 2, air quality index 60-80, or a temperature anomaly of +1 °C or more.
            - `ALERT`: bleaching alert level 3-4, or air quality index above 80.""";
}
