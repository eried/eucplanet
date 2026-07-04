# In-app diagnostics guide (reporting a problem)

Sometimes a wheel connects fine but something looks off: the speed reads
wrong, the battery jumps around, a temperature is missing, or the light /
horn / lock button doesn't do what it should. EUC Planet has a built-in
**Service Mode** that records exactly what the app sees while the problem
happens. A 5-minute recording from you is usually all the maintainer needs
to track it down.

This matters because the maintainer only owns one wheel (an InMotion V14).
Every other wheel is fixed from recordings sent in by the people who ride
them. Yours is what makes a fix possible.

> For a wheel that isn't supported **at all** yet, use the
> [BLE capture guide](BLE_CAPTURE_GUIDE.md) instead. This guide is for a
> wheel that already connects but misbehaves.

## What you'll send back

One `.txt` log, shared straight from the app. That's the whole thing.

## Before you start

- Have somewhere safe to do a short, slow ride if the
  problem is about speed, distance, or battery.
- Make sure only EUC Planet is connected to the wheel (close the
  manufacturer's app).
- Know what looks wrong before you start, so you can describe it in a word
  or two.

## 1. Enter Service Mode

1. **Disconnect** the wheel in EUC Planet first, so the recording captures
   the connection from the very start.
2. Tap the **version number** in the bottom-right corner of the dashboard.
3. In the About box that opens, **press and hold the app logo**. Keep your
   finger on it; sliding off cancels.
4. Read the warning, confirm, and tap **Enter**.

> [!NOTE]
> When version number now **pulses red**. That means it's already recording.

<img width="200" alt="Screenshot_1780217539" src="https://github.com/user-attachments/assets/9d074fa6-5e46-45a9-adce-bc0ae220f237" />

## 2. Describe the problem

A panel opens with a comment box near the top ("Add comment to log"). Type
a short note about what's wrong and tap send. The app already records your
wheel model, firmware, battery, voltage and speed automatically, so you
don't need to type those; just describe the problem in plain words.

Examples:

```
Speed looks too low, feels like 25 but app shows 18
Battery % jumps from 60 to 45 and back while riding
No temperature shown anywhere, V11
Horn button in the app does nothing
```

## 3. Make the problem happen

1. Tap the **X** in the top-left to close the panel (not the **Stop** symbol). It keeps recording in
   the background (the version number stays red).
2. **Connect** to your wheel as normal and reproduce the problem:
   - speed / battery / distance issue: ride a little, slow and safe, and
     vary your pace
   - button issue (light, horn, lock): tap that control a few times
3. The single most useful thing you can do: the moment the problem is
   visible on screen, tap the red version number to reopen the panel, add a
   comment like `speedo shows 22 now, my phone GPS says 27`, and send it.
   That one timestamped line ties what you saw to what the app recorded.

You can reopen and add comments as many times as you like.

## 4. Share the log

1. Tap the red version number to reopen the panel.
2. Tap the **Share** icon in the top-right.
3. Send the `.txt` to the maintainer, by email or attached to a GitHub
   issue.

## 5. When you're done

The **Stop** icon (top-right) exits Service Mode and clears the log. If you
forget, it clears itself when you close the app; nothing is kept around.

## A couple of notes

- **Privacy**: nothing leaves your phone until you tap Share. The log is
  plain text, you can open and read it first.
- **Ignore the Commands / Inspector / Raw tabs.** Those are for protocol
  research; the maintainer will ask only if they're ever needed.

Thanks for helping out.
