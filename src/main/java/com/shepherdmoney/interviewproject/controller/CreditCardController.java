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
import java.time.temporal.ChronoUnit;
import java.util.*;


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
            if (datesAreOnSameDay(transaction.getTransactionTime().atZone(ZoneOffset.UTC), mostRecentHistory.getDate().atZone(ZoneOffset.UTC))) {
                mostRecentHistory.setBalance(mostRecentHistory.getBalance() + transactionAmount);
            } else {
                // Otherwise, may need to add balance histories in between update date and existing dates

                Instant now = Instant.now();
                if (transaction.getTransactionTime().isAfter(now)) {
                    return ResponseEntity.badRequest().build();
                }

                Iterator<BalanceHistory> balanceHistoryIterator = oldBalanceHistories.iterator();
                BalanceHistory previousBalanceHistory = balanceHistoryIterator.next();
                ArrayList<BalanceHistory> newBalanceHistories = new ArrayList<>();
                newBalanceHistories.add(previousBalanceHistory);
                previousBalanceHistory.setBalance(previousBalanceHistory.getBalance() + transactionAmount);

                boolean needToAddLastTransaction = true;

                while (balanceHistoryIterator.hasNext()) {
                    BalanceHistory olderHistory = balanceHistoryIterator.next();

                    // If day of transaction found, no need to modify older balance histories
                    if (datesAreOnSameDay(olderHistory.getDate().atZone(ZoneOffset.UTC), transaction.getTransactionTime().atZone(ZoneOffset.UTC))) {
                        newBalanceHistories.add(olderHistory);
                        olderHistory.setBalance(olderHistory.getBalance() + transactionAmount);
                        // Add remaining, older balance histories without modification
                        while (balanceHistoryIterator.hasNext()) {
                            newBalanceHistories.add(balanceHistoryIterator.next());
                        }
                        needToAddLastTransaction = false;
                        break;
                    }
                    addInBetweenBalanceHistories(newBalanceHistories, cardToUpdate, transaction.getTransactionTime(), previousBalanceHistory.getDate(), transactionAmount);

                    newBalanceHistories.add(olderHistory);
                    olderHistory.setBalance(olderHistory.getBalance() + transactionAmount);

                    previousBalanceHistory = olderHistory;
                }

                if (needToAddLastTransaction) {
                    addInBetweenBalanceHistories(newBalanceHistories, cardToUpdate, transaction.getTransactionTime(), previousBalanceHistory.getDate(), transactionAmount);

                    BalanceHistory oldestBalanceHistory = new BalanceHistory();
                    oldestBalanceHistory.setBalance(transactionAmount);
                    oldestBalanceHistory.setCard(cardToUpdate);
                    oldestBalanceHistory.setDate(transaction.getTransactionTime());

                    newBalanceHistories.add(oldestBalanceHistory);
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
     * @param balanceHistories List to add to.
     * @param cardToUpdate Credit card to add balances histories to.
     * @param startDate starting date.
     * @param endDate ending date.
     * @param amount Balance amount of each balance history to add.
     */
    private void addInBetweenBalanceHistories(List<BalanceHistory> balanceHistories, CreditCard cardToUpdate, Instant startDate, Instant endDate, double amount) {
        for (int dayOffset = 1; dayOffset < ChronoUnit.DAYS.between(startDate, endDate); dayOffset++) {
            BalanceHistory newInBetweenBalanceHistory = new BalanceHistory();
            newInBetweenBalanceHistory.setCard(cardToUpdate);
            newInBetweenBalanceHistory.setBalance(amount);
            newInBetweenBalanceHistory.setDate(endDate.atOffset(ZoneOffset.UTC).minusDays(dayOffset).toInstant());
            balanceHistories.add(newInBetweenBalanceHistory);
        }
    }

    /**
     * Creates a balance history for the current day if it does not already exist in the given card.
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
            // No transactions since mostRecentHistory, so balance is still same
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
                // No transactions since mostRecentHistory, so balance is still same
                todayBalance.setBalance(0);

                balanceHistory.add(0, todayBalance);
                card.setBalanceHistory(balanceHistory);
            }
        }
    }

    /**
     * Checks whether two DateTimes are on the same day in terms of day of year. This is not day of week.
     * For example, two dates with the date being 2023-04-22 would return true.
     * Dates 2023-05-22 and 2023-02-22 would return false.
     * @param date1 First date to check.
     * @param date2 Second date to check.
     * @return True if DatTimes are on the same day. False otherwise.
     */
    private boolean datesAreOnSameDay(ZonedDateTime date1, ZonedDateTime date2) {
        return date1.getDayOfYear() == date2.getDayOfYear() && date1.getYear() == date2.getYear();
    }
}
