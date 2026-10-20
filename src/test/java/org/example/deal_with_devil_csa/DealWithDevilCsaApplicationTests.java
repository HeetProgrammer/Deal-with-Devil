package org.example.deal_with_devil_csa;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DealWithDevilCsaApplicationTests
{
    static
    {
        // Same fix as main(): tests skip main(), and a JVM that reports "Asia/Calcutta" can make PostgreSQL reject the connection.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @Test
    void contextLoads()
    {
    }

}
