package org.example.deal_with_devil_csa;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DealWithDevilCsaApplication
{

    public static void main(String[] args)
    {
        // Must run before Spring starts, so the JDBC driver sends a valid time zone name to PostgreSQL
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(DealWithDevilCsaApplication.class, args);
    }

}
