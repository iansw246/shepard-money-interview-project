package com.shepherdmoney.interviewproject.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Cascade;

import java.util.List;

import static org.hibernate.annotations.CascadeType.MERGE;
import static org.hibernate.annotations.CascadeType.PERSIST;

@Entity
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class CreditCard {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;

    private String issuanceBank;

    private String number;

    @ManyToOne
    User owner;

    @OneToMany(mappedBy = "card")
    @OrderBy(value = "date DESC")
    @Cascade({MERGE, PERSIST})
    private List<BalanceHistory> balanceHistory;
}
