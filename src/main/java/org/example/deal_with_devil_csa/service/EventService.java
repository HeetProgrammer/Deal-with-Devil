package org.example.deal_with_devil_csa.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.GameEvent;
import org.example.deal_with_devil_csa.repository.GameEventRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.web.dto.EventResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Creates and serves the notification feed. Events are written inside the same transaction as the thing they
 * announce (a ledger entry, a settlement, a phase change), so an event exists if and only if the change happened.
 * The frontend polls the feed with "everything after the last id I saw".
 *
 * personal() and broadcast() are internal writers called by the admin and settlement services, which are already
 * restricted to admins, so they carry no check of their own. Reading the feed is only allowed for the owner.
 */
@Service
@RequiredArgsConstructor
public class EventService
{
    private static final int FIRST_PAGE_SIZE = 20;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final GameEventRepository eventRepository;
    private final UserRepository userRepository;

    /** An event for one person. Shown to them as a pop-up. */
    @Transactional
    public void personal(Long userId, EventType type, String message, Long amount, Long balanceAfter,
                         Integer corruptionLevel, Long problemId)
    {
        GameEvent event = new GameEvent();
        event.setUser(userRepository.getReferenceById(userId));
        fill(event, type, message, amount, balanceAfter, corruptionLevel, problemId);
        eventRepository.save(event);
    }

    /** An event for everybody. Shown in the notification list only. */
    @Transactional
    public void broadcast(EventType type, String message)
    {
        GameEvent event = new GameEvent();
        fill(event, type, message, null, null, null, null);
        eventRepository.save(event);
    }

    /**
     * Without {@code after}: the newest events (20 unless a limit is given), oldest first, to fill the notification list.
     * With {@code after}: only events with a higher id, oldest first (limit defaults to 50, at most 200).
     */
    @Transactional(readOnly = true)
    @PreAuthorize("@guard.isSelf(#userId)")
    public List<EventResponse> feed(Long userId, Long after, Integer limit)
    {
        List<GameEvent> events;
        if (after == null)
        {
            int size = limit == null ? FIRST_PAGE_SIZE : clamp(limit);
            events = new ArrayList<>(eventRepository.findLatestFeed(userId, PageRequest.of(0, size)));
            Collections.reverse(events);
        }
        else
        {
            int size = limit == null ? DEFAULT_LIMIT : clamp(limit);
            events = eventRepository.findFeedAfter(userId, after, PageRequest.of(0, size));
        }
        return events.stream().map(EventResponse::from).toList();
    }

    private static int clamp(int limit)
    {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static void fill(GameEvent event, EventType type, String message, Long amount, Long balanceAfter,
                             Integer corruptionLevel, Long problemId)
    {
        event.setType(type);
        event.setMessage(message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message);
        event.setAmount(amount);
        event.setBalanceAfter(balanceAfter);
        event.setCorruptionLevel(corruptionLevel);
        event.setProblemId(problemId);
    }
}
