package btools.router;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The app reads turn hints through these getters, from another package. */
public class VoiceHintAccessTest {

  @Test
  public void gettersReadTheHint() {
    VoiceHint h = new VoiceHint();
    h.cmd = VoiceHint.TL;
    h.roundaboutExit = 3;
    h.indexInTrack = 7;
    h.distanceToNext = 12.5;
    h.angle = -90f;
    assertEquals(VoiceHint.TL, h.getCommand());
    assertEquals(3, h.getExitNumber());
    assertEquals(7, h.getIndexInTrack());
    assertEquals(12.5, h.getDistanceToNext(), 0.0);
    assertEquals(-90f, h.getAngle(), 0f);
    VoiceHintList l = new VoiceHintList();
    l.list.add(h);
    assertEquals(1, l.getList().size());
  }
}
