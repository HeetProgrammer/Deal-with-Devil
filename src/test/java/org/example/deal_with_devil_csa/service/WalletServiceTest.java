package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests (no database, no Spring): repositories are mocked, so these check the wallet rules themselves.
 * Row locking and the unique reversalOf constraint are database behaviour and belong in integration tests.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest
{
    private static final long USER_ID = 1L;
    private static final long ADMIN_ID = 9L;

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private UserRepository userRepository;

    private WalletService service;
    private User user;
    private User admin;
    private Wallet wallet;

    @BeforeEach
    void setUp()
    {
        service = new WalletService(walletRepository, ledgerEntryRepository, userRepository);

        user = new User();
        user.setId(USER_ID);
        admin = new User();
        admin.setId(ADMIN_ID);

        wallet = new Wallet(user);
        wallet.setBalance(100L);

        lenient().when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        lenient().when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.getReferenceById(ADMIN_ID)).thenReturn(admin);
    }

    // ---- credit ----

    @Test
    void creditAddsCoinsAndWritesLedgerEntry()
    {
        LedgerEntry entry = service.credit(USER_ID, 50, LedgerType.PRESET_CREDIT, "Solved Bronze problem", ADMIN_ID);

        assertThat(wallet.getBalance()).isEqualTo(150L);
        assertThat(entry.getAmount()).isEqualTo(50L);
        assertThat(entry.getBalanceAfter()).isEqualTo(150L);
        assertThat(entry.getType()).isEqualTo(LedgerType.PRESET_CREDIT);
        assertThat(entry.getReason()).isEqualTo("Solved Bronze problem");
        assertThat(entry.getActor()).isSameAs(admin);
        assertThat(entry.getUser()).isSameAs(user);
        assertThat(entry.getReversalOf()).isNull();
    }

    @Test
    void creditWithoutActorIsASystemAction()
    {
        LedgerEntry entry = service.credit(USER_ID, 10, LedgerType.CUSTOM, "System bonus", null);

        assertThat(entry.getActor()).isNull();
    }

    @Test
    void reasonIsTrimmed()
    {
        LedgerEntry entry = service.credit(USER_ID, 10, LedgerType.CUSTOM, "  padded reason  ", ADMIN_ID);

        assertThat(entry.getReason()).isEqualTo("padded reason");
    }

    @Test
    void creditRejectsZeroAndNegativeAmounts()
    {
        assertApiError(() -> service.credit(USER_ID, 0, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.INVALID_AMOUNT);
        assertApiError(() -> service.credit(USER_ID, -5, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.INVALID_AMOUNT);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditRejectsBlankReason()
    {
        assertApiError(() -> service.credit(USER_ID, 10, LedgerType.CUSTOM, "   ", ADMIN_ID),
                ErrorCode.VALIDATION_FAILED);
        assertApiError(() -> service.credit(USER_ID, 10, LedgerType.CUSTOM, null, ADMIN_ID),
                ErrorCode.VALIDATION_FAILED);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void creditRejectsOverflowInsteadOfWrappingAround()
    {
        wallet.setBalance(Long.MAX_VALUE);

        assertApiError(() -> service.credit(USER_ID, 1, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.INVALID_AMOUNT);

        assertThat(wallet.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void creditCannotCreateReversalEntries()
    {
        assertThatThrownBy(() -> service.credit(USER_ID, 10, LedgerType.REVERSAL, "x", ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownWalletGivesUserNotFound()
    {
        assertApiError(() -> service.credit(2L, 10, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.USER_NOT_FOUND);
    }

    // ---- deduct ----

    @Test
    void deductRemovesCoinsAndStoresNegativeAmount()
    {
        LedgerEntry entry = service.deduct(USER_ID, 30, LedgerType.HINT_DEDUCTION, "Hint for problem #2", ADMIN_ID);

        assertThat(wallet.getBalance()).isEqualTo(70L);
        assertThat(entry.getAmount()).isEqualTo(-30L);
        assertThat(entry.getBalanceAfter()).isEqualTo(70L);
        assertThat(entry.getType()).isEqualTo(LedgerType.HINT_DEDUCTION);
    }

    @Test
    void deductDownToExactlyZeroIsAllowed()
    {
        LedgerEntry entry = service.deduct(USER_ID, 100, LedgerType.CUSTOM, "Spend everything", ADMIN_ID);

        assertThat(wallet.getBalance()).isZero();
        assertThat(entry.getBalanceAfter()).isZero();
    }

    @Test
    void deductBelowZeroIsBlockedAndNothingChanges()
    {
        assertApiError(() -> service.deduct(USER_ID, 101, LedgerType.CUSTOM, "Too much", ADMIN_ID),
                ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void deductRejectsZeroAndNegativeAmounts()
    {
        assertApiError(() -> service.deduct(USER_ID, 0, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.INVALID_AMOUNT);
        assertApiError(() -> service.deduct(USER_ID, -5, LedgerType.CUSTOM, "x", ADMIN_ID), ErrorCode.INVALID_AMOUNT);
    }

    // ---- reverse (undo) ----

    @Test
    void reversingACreditTakesTheCoinsBack()
    {
        LedgerEntry original = entry(5L, LedgerType.PRESET_CREDIT, 50L);
        when(ledgerEntryRepository.findById(5L)).thenReturn(Optional.of(original));

        LedgerEntry reversal = service.reverse(5L, "Wrong person", ADMIN_ID);

        assertThat(wallet.getBalance()).isEqualTo(50L);
        assertThat(reversal.getType()).isEqualTo(LedgerType.REVERSAL);
        assertThat(reversal.getAmount()).isEqualTo(-50L);
        assertThat(reversal.getBalanceAfter()).isEqualTo(50L);
        assertThat(reversal.getReversalOf()).isEqualTo(5L);
        assertThat(reversal.getReason()).isEqualTo("Wrong person");
    }

    @Test
    void reversingADeductionGivesTheCoinsBack()
    {
        LedgerEntry original = entry(6L, LedgerType.HINT_DEDUCTION, -30L);
        when(ledgerEntryRepository.findById(6L)).thenReturn(Optional.of(original));

        LedgerEntry reversal = service.reverse(6L, "Hint was not given", ADMIN_ID);

        assertThat(wallet.getBalance()).isEqualTo(130L);
        assertThat(reversal.getAmount()).isEqualTo(30L);
    }

    @Test
    void theOriginalEntryIsNeverModifiedByAnUndo()
    {
        LedgerEntry original = entry(5L, LedgerType.PRESET_CREDIT, 50L);
        original.setBalanceAfter(150L);
        when(ledgerEntryRepository.findById(5L)).thenReturn(Optional.of(original));

        service.reverse(5L, "Undo", ADMIN_ID);

        assertThat(original.getAmount()).isEqualTo(50L);
        assertThat(original.getBalanceAfter()).isEqualTo(150L);
        assertThat(original.getType()).isEqualTo(LedgerType.PRESET_CREDIT);
    }

    @Test
    void anEntryCanOnlyBeUndoneOnce()
    {
        LedgerEntry original = entry(5L, LedgerType.PRESET_CREDIT, 50L);
        when(ledgerEntryRepository.findById(5L)).thenReturn(Optional.of(original));
        when(ledgerEntryRepository.existsByReversalOf(5L)).thenReturn(true);

        assertApiError(() -> service.reverse(5L, "Again", ADMIN_ID), ErrorCode.CONFLICT);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void aReversalCannotBeUndone()
    {
        LedgerEntry reversal = entry(7L, LedgerType.REVERSAL, -50L);
        when(ledgerEntryRepository.findById(7L)).thenReturn(Optional.of(reversal));

        assertApiError(() -> service.reverse(7L, "Undo the undo", ADMIN_ID), ErrorCode.CONFLICT);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void undoIsBlockedWhenItWouldTakeTheBalanceBelowZero()
    {
        // 500 was credited, but the participant has since spent down to 100.
        LedgerEntry original = entry(8L, LedgerType.PRESET_CREDIT, 500L);
        when(ledgerEntryRepository.findById(8L)).thenReturn(Optional.of(original));

        assertApiError(() -> service.reverse(8L, "Mistake", ADMIN_ID), ErrorCode.INSUFFICIENT_BALANCE);

        assertThat(wallet.getBalance()).isEqualTo(100L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void undoRequiresAReason()
    {
        LedgerEntry original = entry(5L, LedgerType.PRESET_CREDIT, 50L);
        when(ledgerEntryRepository.findById(5L)).thenReturn(Optional.of(original));

        assertApiError(() -> service.reverse(5L, " ", ADMIN_ID), ErrorCode.VALIDATION_FAILED);

        assertThat(wallet.getBalance()).isEqualTo(100L);
    }

    @Test
    void undoingAMissingEntryGivesNotFound()
    {
        assertApiError(() -> service.reverse(404L, "Whatever", ADMIN_ID), ErrorCode.NOT_FOUND);
    }

    // ---- history ----

    @Test
    void historyReturnsTheRepositoryListUnchanged()
    {
        LedgerEntry newer = entry(2L, LedgerType.CUSTOM, 10L);
        LedgerEntry older = entry(1L, LedgerType.CUSTOM, 20L);
        when(ledgerEntryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(newer, older));

        assertThat(service.history(USER_ID)).containsExactly(newer, older);
    }

    // ---- helpers ----

    private LedgerEntry entry(Long id, LedgerType type, long amount)
    {
        LedgerEntry e = new LedgerEntry();
        e.setId(id);
        e.setUser(user);
        e.setType(type);
        e.setAmount(amount);
        e.setReason("seed");
        return e;
    }

    private static void assertApiError(ThrowingCallable call, ErrorCode expected)
    {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
