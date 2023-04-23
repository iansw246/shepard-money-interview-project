package com.shepherdmoney.interviewproject.controller;

import com.shepherdmoney.interviewproject.model.BalanceHistory;
import com.shepherdmoney.interviewproject.model.CreditCard;
import com.shepherdmoney.interviewproject.model.User;
import com.shepherdmoney.interviewproject.repository.CreditCardRepository;
import com.shepherdmoney.interviewproject.repository.UserRepository;
import com.shepherdmoney.interviewproject.vo.request.AddCreditCardToUserPayload;
import com.shepherdmoney.interviewproject.vo.request.UpdateBalancePayload;
import com.shepherdmoney.interviewproject.vo.response.CreditCardView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;


@RestController
public class CreditCardController {
    @Autowired
    private CreditCardRepository creditCardRepository;
    @Autowired
    private UserRepository userRepository;

    @PostMapping("/credit-card")
    public ResponseEntity<Integer> addCreditCardToUser(@RequestBody AddCreditCardToUserPayload payload) {
        Optional<User> cardOwnerOptional = userRepository.findById(payload.getUserId());

        // Verify that the user the new card will be associated with exists
        if (cardOwnerOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Already verified user is not empty, so should not throw
        User cardOwner = cardOwnerOptional.get();

        CreditCard newCreditCard = new CreditCard();
        newCreditCard.setIssuanceBank(payload.getCardIssuanceBank());
        newCreditCard.setNumber(payload.getCardNumber());
        newCreditCard.setOwner(cardOwner);
        populateTodayBalanceIfNotExist(newCreditCard);

        cardOwner.getCreditCardList().add(newCreditCard);

        CreditCard savedCreditCard = creditCardRepository.save(newCreditCard);
        userRepository.save(cardOwner);

        return ResponseEntity.ok(savedCreditCard.getId());
    }

    @GetMapping("/credit-card:all")
    public ResponseEntity<List<CreditCardView>> getAllCardOfUser(@RequestParam int userId) {
        Optional<User> optionalUser = userRepository.findById(userId);

        if (optionalUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = optionalUser.get();
        return ResponseEntity.ok(user.getCreditCardList()
                .stream().map(
                        card -> new CreditCardView(card.getIssuanceBank(), card.getNumber())
                ).toList());
    }

    @GetMapping("/credit-card:user-id")
    public ResponseEntity<Integer> getUserIdForCreditCard(@RequestParam String creditCardNumber) {
        List<CreditCard> cardsWithNumber = creditCardRepository.findByNumber(creditCardNumber);

        // 0 or multiple users with that card, which is ambiguous
        if (cardsWithNumber.size() != 1) {
            return ResponseEntity.badRequest().build();
        } else {
            CreditCard firstMatchingCard = cardsWithNumber.get(0);
            User cardOwner = firstMatchingCard.getOwner();
            return ResponseEntity.ok(cardOwner.getId());
        }
    }

    @PostMapping("/credit-card:update-balance")
    public ResponseEntity<String> updateCreditCardBalance(@RequestBody UpdateBalancePayload[] payload) {
        for (UpdateBalancePayload transaction : payload) {
            String numberOfCardToUpdate = transaction.getCreditCardNumber();
            List<CreditCard> cardsWithNumber = creditCardRepository.findByNumber(numberOfCardToUpdate);
            // No cards with given card number or ambiguous case of multiple cards with given card number
            if (cardsWithNumber.size() != 1) {
                return ResponseEntity.badRequest().build();
            }
            CreditCard cardToUpdate = cardsWithNumber.get(0);

            populateTodayBalanceIfNotExist(cardToUpdate);
            List<BalanceHistory> oldBalanceHistories = cardToUpdate.getBalanceHistory();

            BalanceHistory mostRecentHistory = oldBalanceHistories.get(0);

            double transactionAmount = transaction.getTransactionAmount();

            // If transaction is on the most recent date, then only need to update that most recent transaction
            ZonedDateTime transactionZonedDateTime = transaction.getTransactionTime().atZone(ZoneOffset.UTC);
            if (datesAreOnSameDay(transactionZonedDateTime, mostRecentHistory.getDate().atZone(ZoneOffset.UTC))) {
                mostRecentHistory.setBalance(mostRecentHistory.getBalance() + transactionAmount);
            } else {
                // Otherwise, may need to add balance histories in between update date and existing dates

                Instant now = Instant.now();
                if (transaction.getTransactionTime().isAfter(now)) {
                    return ResponseEntity.badRequest().body("Transaction is in the future.");
                }


                BalanceHistory transactionBalanceHistory = new BalanceHistory();
                transactionBalanceHistory.setCard(cardToUpdate);
                transactionBalanceHistory.setDate(transaction.getTransactionTime());

                boolean transactionBalanceHistoryAdded = false;

                // Insert transaction history into correct position in oldBalanceHistories
                for (int i = 0; i < oldBalanceHistories.size(); i++) {
                    BalanceHistory currentBalanceHistory = oldBalanceHistories.get(i);
                    if (datesAreOnSameDay(currentBalanceHistory.getDate().atZone(ZoneOffset.UTC),
                            transactionZonedDateTime)) {
                        currentBalanceHistory.setBalance(currentBalanceHistory.getBalance());
                        transactionBalanceHistory = currentBalanceHistory;
                        transactionBalanceHistoryAdded = true;
                        break;
                    } else if (currentBalanceHistory.getDate().isBefore(transaction.getTransactionTime())) {
                        // We know current and transaction occurred on different dates, so if current is before,
                        // transaction must be inserted here

                        transactionBalanceHistory.setBalance(currentBalanceHistory.getBalance());
                        oldBalanceHistories.add(i, transactionBalanceHistory);
                        transactionBalanceHistoryAdded = true;
                        break;
                    }
                }

                // Insert at end if it is after all existing balance histories
                // If it wasn't added in between any existing balance histories
                if (!transactionBalanceHistoryAdded) {
                    oldBalanceHistories.add(transactionBalanceHistory);
                }

                // Fill in dates between existing dates

                ArrayList<BalanceHistory> newBalanceHistories = new ArrayList<>();

                Iterator<BalanceHistory> oldBalanceHistoryIterator = oldBalanceHistories.iterator();

                // First entry must be today, so no possible gaps in dates
                BalanceHistory previousBalanceHistory = oldBalanceHistoryIterator.next();
                newBalanceHistories.add(previousBalanceHistory);
                previousBalanceHistory.setBalance(previousBalanceHistory.getBalance() + transactionAmount);

                while (oldBalanceHistoryIterator.hasNext()) {
                    BalanceHistory currentBalanceHistory = oldBalanceHistoryIterator.next();
                    // If we reach the date of the transaction, can stop modifying balances
                    if (currentBalanceHistory.equals(transactionBalanceHistory)) {
                        currentBalanceHistory.setBalance(currentBalanceHistory.getBalance() + transactionAmount);
                        addInBetweenBalanceHistories(newBalanceHistories, cardToUpdate,
                                currentBalanceHistory.getDate(), previousBalanceHistory.getDate(),
                                currentBalanceHistory.getBalance());
                        newBalanceHistories.add(currentBalanceHistory);
                        // Add remaining balances unchanged since they are after the transaction date
                        oldBalanceHistoryIterator.forEachRemaining(newBalanceHistories::add);
                        break;
                    }
                    addInBetweenBalanceHistories(newBalanceHistories, cardToUpdate, currentBalanceHistory.getDate(),
                            previousBalanceHistory.getDate(), currentBalanceHistory.getBalance() + transactionAmount);
                    newBalanceHistories.add(currentBalanceHistory);
                    currentBalanceHistory.setBalance(currentBalanceHistory.getBalance() + transactionAmount);
                    previousBalanceHistory = currentBalanceHistory;
                }

                cardToUpdate.setBalanceHistory(newBalanceHistories);
            }

            creditCardRepository.save(cardToUpdate);
        }

        return ResponseEntity.ok("Ok");
    }

    /**
     * Add balance histories to given list for dates between startDate and endDate.
     * Does not add balance histories for the startDate and endDate.
     *
     * @param balanceHistories List to add to.
     * @param cardToUpdate     Credit card to add balances histories to.
     * @param startDate        starting date.
     * @param endDate          ending date.
     * @param amount           Balance amount of each balance history to add.
     */
    private void addInBetweenBalanceHistories(List<BalanceHistory> balanceHistories, CreditCard cardToUpdate,
                                              Instant startDate, Instant endDate, double amount) {
        ZonedDateTime currentDateTime = startDate.atZone(ZoneOffset.UTC).plusDays(1);
        ZonedDateTime endDateTime = endDate.atZone(ZoneOffset.UTC);
        while (currentDateTime.isBefore(endDateTime) && !datesAreOnSameDay(currentDateTime, endDateTime)) {
            BalanceHistory newInBetweenBalanceHistory = new BalanceHistory();
            newInBetweenBalanceHistory.setCard(cardToUpdate);
            newInBetweenBalanceHistory.setBalance(amount);
            newInBetweenBalanceHistory.setDate(currentDateTime.toInstant());
            balanceHistories.add(newInBetweenBalanceHistory);
            currentDateTime = currentDateTime.plusDays(1);
        }
    }

    /**
     * Creates a balance history for the current day if it does not already exist in the given card.
     *
     * @param card Credit card to add today's balance to.
     */
    private void populateTodayBalanceIfNotExist(CreditCard card) {
        List<BalanceHistory> balanceHistory = card.getBalanceHistory();
        if (balanceHistory == null) {
            balanceHistory = new ArrayList<>();
        }

        if (balanceHistory.isEmpty()) {
            BalanceHistory todayBalance = new BalanceHistory();
            todayBalance.setCard(card);
            todayBalance.setDate(Instant.now());
            todayBalance.setBalance(0);

            balanceHistory.add(0, todayBalance);
            card.setBalanceHistory(balanceHistory);
        } else {
            Instant now = Instant.now();
            BalanceHistory mostRecentHistory = balanceHistory.get(0);
            if (!datesAreOnSameDay(mostRecentHistory.getDate().atZone(ZoneOffset.UTC), now.atZone(ZoneOffset.UTC))) {
                BalanceHistory todayBalance = new BalanceHistory();
                todayBalance.setCard(card);
                todayBalance.setDate(Instant.now());
                // No transactions since mostRecentHistory, so balance is still same as before
                todayBalance.setBalance(mostRecentHistory.getBalance());

                balanceHistory.add(0, todayBalance);
                card.setBalanceHistory(balanceHistory);
            }
        }
    }

    /**
     * Checks whether two DateTimes are on the same day in terms of day of year. This is not day of week.
     * For example, two dates with the date being 2023-04-22 would return true.
     * Dates 2023-05-22 and 2023-02-22 would return false.
     *
     * @param date1 First date to check.
     * @param date2 Second date to check.
     * @return True if DatTimes are on the same day. False otherwise.
     */
    private boolean datesAreOnSameDay(ZonedDateTime date1, ZonedDateTime date2) {
        return date1.getDayOfYear() == date2.getDayOfYear() && date1.getYear() == date2.getYear();
    }
}
