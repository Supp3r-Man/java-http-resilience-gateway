package com.github.sup3rmar10.gateway;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Count-based circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN.
 *
 * <p>Callers {@link #acquire()} a permit (which returns the current generation, or -1 if rejected) and later
 * {@link #record(long, boolean)} the outcome with that generation. Outcomes from a previous generation (a
 * request admitted before the breaker changed state) are ignored, so stragglers cannot close or trip it.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Transition(State from, State to, long atMillis) {
        @Override
        public String toString() {
            return from + "->" + to + "@" + atMillis;
        }
    }

    private final Clock clock;
    private final int windowSize;
    private final int minCalls;
    private final double failureRate;
    private final long openMillis;
    private final int halfOpenPermits;
    private final Consumer<Transition> listener;

    private final boolean[] window;
    private int filled;
    private int next;
    private int failures;
    private State state = State.CLOSED;
    private long openedAt;
    private long generation;
    private int halfOpenInFlight;
    private final List<Transition> transitions = new ArrayList<>();

    public CircuitBreaker(Clock clock, int windowSize, int minCalls, double failureRate, long openMillis,
                          int halfOpenPermits, Consumer<Transition> listener) {
        if (windowSize < 1 || minCalls < 1 || minCalls > windowSize) {
            throw new IllegalArgumentException("need 1 <= minCalls <= windowSize");
        }
        if (failureRate <= 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate must be in (0, 1]");
        }
        this.clock = clock;
        this.windowSize = windowSize;
        this.minCalls = minCalls;
        this.failureRate = failureRate;
        this.openMillis = openMillis;
        this.halfOpenPermits = Math.max(1, halfOpenPermits);
        this.listener = listener;
        this.window = new boolean[windowSize];
    }

    public CircuitBreaker(Clock clock, int windowSize, int minCalls, double failureRate, long openMillis,
                          int halfOpenPermits) {
        this(clock, windowSize, minCalls, failureRate, openMillis, halfOpenPermits, t -> { });
    }

    /** Returns the generation to pass to {@link #record}, or -1 when the call is rejected. */
    public synchronized long acquire() {
        if (state == State.OPEN) {
            if (clock.millis() - openedAt >= openMillis) {
                transition(State.HALF_OPEN);
            } else {
                return -1;
            }
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenInFlight >= halfOpenPermits) {
                return -1;
            }
            halfOpenInFlight++;
        }
        return generation;
    }

    public synchronized void record(long gen, boolean success) {
        if (gen != generation) {
            return;
        }
        switch (state) {
            case CLOSED -> {
                push(!success);
                if (filled >= minCalls && (double) failures / filled >= failureRate) {
                    transition(State.OPEN);
                }
            }
            case HALF_OPEN -> transition(success ? State.CLOSED : State.OPEN);
            case OPEN -> { }
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized List<Transition> transitions() {
        return List.copyOf(transitions);
    }

    private void push(boolean failure) {
        if (filled == windowSize) {
            if (window[next]) {
                failures--;
            }
        } else {
            filled++;
        }
        window[next] = failure;
        if (failure) {
            failures++;
        }
        next = (next + 1) % windowSize;
    }

    private void transition(State to) {
        Transition t = new Transition(state, to, clock.millis());
        state = to;
        generation++;
        filled = 0;
        next = 0;
        failures = 0;
        halfOpenInFlight = 0;
        java.util.Arrays.fill(window, false);
        if (to == State.OPEN) {
            openedAt = t.atMillis();
        }
        transitions.add(t);
        listener.accept(t);
    }
}
