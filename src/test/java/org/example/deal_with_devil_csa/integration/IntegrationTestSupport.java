package org.example.deal_with_devil_csa.integration;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.repository.AuditLogRepository;
import org.example.deal_with_devil_csa.repository.BidRepository;
import org.example.deal_with_devil_csa.repository.GameEventRepository;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.repository.ProblemHintRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

/**
 * Shared set-up for the integration tests: empty tables before every test, a MockMvc with the real security chain,
 * and small helpers to create users, rounds, problems and bids directly in the database and to call the API as a
 * given user. Every concrete test class puts its own @SpringBootTest on itself (with the constants below, so they all
 * use the separate test database and share one application context where the properties match).
 */
abstract class IntegrationTestSupport
{
    /** The separate test database (create it once with CREATE DATABASE deal_devil_test). Never the dev database. */
    static final String DB_URL_PROPERTY =
            "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5433/deal_devil_test}";
    static final String DDL_PROPERTY = "spring.jpa.hibernate.ddl-auto=create-drop";

    static
    {
        // Same fix as main(): tests skip main(), and a JVM that reports "Asia/Calcutta" can make PostgreSQL reject the connection.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired protected WebApplicationContext webContext;
    @Autowired protected JwtService jwtService;

    @Autowired protected UserRepository userRepository;
    @Autowired protected WalletRepository walletRepository;
    @Autowired protected RoundRepository roundRepository;
    @Autowired protected ProblemRepository problemRepository;
    @Autowired protected ProblemHintRepository hintRepository;
    @Autowired protected BidRepository bidRepository;
    @Autowired protected LedgerEntryRepository ledgerEntryRepository;
    @Autowired protected GameEventRepository eventRepository;
    @Autowired protected AuditLogRepository auditLogRepository;

    protected MockMvc mockMvc;

    @BeforeEach
    void emptyTables()
    {
        // Children before parents.
        auditLogRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        bidRepository.deleteAllInBatch();
        ledgerEntryRepository.deleteAllInBatch();
        hintRepository.deleteAllInBatch();
        problemRepository.deleteAllInBatch();
        roundRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
    }

    // ---- Calling the API ----

    /** Sends a request signed in as the given user, with an optional JSON body. */
    protected ResultActions send(User who, MockHttpServletRequestBuilder request, String body) throws Exception
    {
        request.header(HttpHeaders.AUTHORIZATION, bearer(who));
        return sendAnonymous(request, body);
    }

    /** Sends a request with no Authorization header, with an optional JSON body. */
    protected ResultActions sendAnonymous(MockHttpServletRequestBuilder request, String body) throws Exception
    {
        if (body != null)
        {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    /** Fires the same request several times at the same moment (as the given user) and returns the HTTP statuses. */
    protected List<Integer> callInParallel(User who, int copies, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(copies);
        try
        {
            CountDownLatch ready = new CountDownLatch(copies);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < copies; i++)
            {
                futures.add(pool.submit(() ->
                {
                    ready.countDown();
                    go.await();
                    return send(who, request.get(), null).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures)
            {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    protected String bearer(User user)
    {
        return "Bearer " + jwtService.issue(user).token();
    }

    protected static String bodyOf(ResultActions result) throws Exception
    {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static long numberAt(String json, String path)
    {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    /** Every number a JSON path matches, for example "$[*].id". */
    protected static List<Long> numbersAt(String json, String path)
    {
        List<?> raw = JsonPath.read(json, path);
        List<Long> numbers = new ArrayList<>();
        for (Object value : raw)
        {
            numbers.add(((Number) value).longValue());
        }
        return numbers;
    }

    // ---- Reading the database ----

    protected long balanceOf(User user)
    {
        return walletRepository.findByUserId(user.getId()).orElseThrow().getBalance();
    }

    // ---- Creating data directly in the database ----

    protected User saveUser(Role role, long coins)
    {
        return saveUser(role, coins, "Flow User " + (COUNTER.get() + 1));
    }

    protected User saveUser(Role role, long coins, String name)
    {
        int n = COUNTER.incrementAndGet();
        User user = new User();
        user.setEmail("flow-user-" + n + "@example.com");
        user.setGoogleId("flow-google-" + n);
        user.setName(name);
        user.setHackerRankId("flow-hr-" + n);
        user.setRole(role);
        user = userRepository.save(user);

        Wallet wallet = new Wallet(user);
        wallet.setBalance(coins);
        walletRepository.save(wallet);
        return user;
    }

    /** A participant straight after the first Google sign-in: no name and no HackerRank ID yet. */
    protected User saveIncompleteUser()
    {
        int n = COUNTER.incrementAndGet();
        User user = new User();
        user.setEmail("flow-new-" + n + "@example.com");
        user.setGoogleId("flow-new-google-" + n);
        user.setRole(Role.PARTICIPANT);
        user = userRepository.save(user);
        walletRepository.save(new Wallet(user));
        return user;
    }

    protected Round saveRound(RoundPhase phase)
    {
        return saveRound(1, phase);
    }

    protected Round saveRound(int number, RoundPhase phase)
    {
        Round round = new Round();
        round.setNumber(number);
        round.setPhase(phase);
        return roundRepository.save(round);
    }

    protected Problem saveProblem(Round round, String title)
    {
        return saveProblem(round, title, Tier.BRONZE, 1, 50);
    }

    protected Problem saveProblem(Round round, String title, Tier tier, int slots, long basePrice)
    {
        Problem problem = new Problem();
        problem.setTitle(title);
        problem.setCategory("Test");
        problem.setTier(tier);
        problem.setSlots(slots);
        problem.setHackerRankLink("https://example.com/" + title.replace(' ', '-'));
        problem.setBasePrice(basePrice);
        problem.setRound(round);
        return problemRepository.save(problem);
    }

    /** A pending bid with a chosen submission time, so tie-breaks can be tested exactly. */
    protected Bid saveBid(User user, Problem problem, long amount, Instant submittedAt)
    {
        Bid bid = new Bid();
        bid.setUser(user);
        bid.setProblem(problem);
        bid.setAmount(amount);
        bid.setSubmittedAt(submittedAt);
        return bidRepository.save(bid);
    }
}
