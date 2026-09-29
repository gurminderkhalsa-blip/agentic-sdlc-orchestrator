package com.agentic.sdlc.gate;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.common.Registry;

@Component
public class GateRegistry extends Registry<Gate> {
    public GateRegistry(List<Gate> gates) {
        super("gate", gates);
    }
}
