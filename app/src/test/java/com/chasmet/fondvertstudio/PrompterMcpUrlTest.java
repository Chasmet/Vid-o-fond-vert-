package com.chasmet.fondvertstudio;
import org.junit.Test;
import static org.junit.Assert.*;
public class PrompterMcpUrlTest {
 @Test public void normalizesSecureServer(){assertEquals("https://example.org",PrompterMcpClient.normalize("https://example.org/mcp")); }
 @Test(expected=IllegalArgumentException.class) public void rejectsPlainHttp(){PrompterMcpClient.normalize("http://example.org/mcp");}
 @Test(expected=IllegalArgumentException.class) public void rejectsTokenizedUrl(){PrompterMcpClient.normalize("https://example.org/mcp?token=x");}
}