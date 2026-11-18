package com.dezxxx.currency.client;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

public interface OxrApi {

    @GetExchange("/latest.json")
    OxrLatestResponse latest(@RequestParam String symbols);
}
