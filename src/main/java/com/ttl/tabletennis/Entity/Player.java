package com.ttl.tabletennis.Entity;

import jakarta.persistence.*;

@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    public Player() {}

    public Player(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName  = lastName;
    }

    // --- getters / setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    /** Convenience used throughout the UI/services. */
    @Transient
    public String getName() {
        String f = firstName == null ? "" : firstName.trim();
        String l = lastName  == null ? "" : lastName.trim();
        return (f + " " + l).trim();
    }

    @Override public String toString() { return getName(); }
}