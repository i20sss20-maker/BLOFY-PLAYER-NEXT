package tv.blofy.player;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaylistEditorContractTest {
    @Test public void newXtreamRequiresCompleteCredentials() {
        PlaylistEditorContract.Prepared missing = PlaylistEditorContract.prepare(
                "", false, "xtream", "", "https://provider.example", "viewer", "", "");
        assertFalse(missing.valid());

        PlaylistEditorContract.Prepared ready = PlaylistEditorContract.prepare(
                "", false, "xtream", "", "https://provider.example", "viewer", "secret", "");
        assertTrue(ready.valid());
        assertEquals("قائمتي", ready.name);
    }

    @Test public void sameKindEditCanChangeOnlyName() {
        PlaylistEditorContract.Prepared edit = PlaylistEditorContract.prepare(
                "xtream", true, "xtream", "السيرفر الثالث", "", "", "", "");
        assertTrue(edit.valid());
        Map<String, String> body = edit.bodyFields();
        assertEquals("السيرفر الثالث", body.get("name"));
        assertFalse(body.containsKey("serverUrl"));
        assertFalse(body.containsKey("username"));
        assertFalse(body.containsKey("password"));
        assertFalse(edit.changesConnection());
    }

    @Test public void changingKindRequiresNewConnectionData() {
        PlaylistEditorContract.Prepared invalid = PlaylistEditorContract.prepare(
                "xtream", true, "m3u", "قائمتي", "", "", "", "");
        assertFalse(invalid.valid());

        PlaylistEditorContract.Prepared valid = PlaylistEditorContract.prepare(
                "xtream", true, "m3u", "قائمتي", "", "", "",
                "http://provider.example/list.m3u8?token=abc");
        assertTrue(valid.valid());
        assertEquals("m3u", valid.bodyFields().get("kind"));
        assertTrue(valid.changesConnection());
    }

    @Test public void rejectsEmbeddedUserInfoAndNonHttpUrls() {
        assertFalse(PlaylistEditorContract.prepare("", false, "xtream", "",
                "ftp://provider.example", "u", "p", "").valid());
        assertFalse(PlaylistEditorContract.prepare("", false, "m3u", "",
                "", "", "", "https://user:pass@provider.example/list.m3u").valid());
    }
}
