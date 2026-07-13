package com.BossAi.bossAi.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestHome {

    @GetMapping("/tadek-dih")
    public String tadekDih() {
        return "John McMuscle & Tadek 5 Strong";
    }

    @GetMapping
    public String sayHello() {
        return "Hello Toucan";
    }

}
