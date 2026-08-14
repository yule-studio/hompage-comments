package studio.yule.comments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "github.token=test-token")
class CommentApiApplicationTests {

    @Test
    void contextLoads() {
        // the wiring itself is the assertion — a missing bean fails here
    }
}
