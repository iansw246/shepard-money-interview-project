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
@Table(name = "MyUser")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;

    private String name;

    private String email;

    // TODO: User's credit card
    // HINT: A user can have one or more, or none at all. We want to be able to query credit cards by user
    //       and user by a credit card.
    @OneToMany(mappedBy = "owner")
    @Cascade({MERGE, PERSIST})
    private List<CreditCard> creditCardList;
}
