# Garage Door

I wanted one thing: a reliable way to open and close my garage door from
the lock screen of my iPhone.

That turned out to be harder than it sounds. Our opener is a myQ, and myQ
does not work with Apple Home. There is no official way in, and the
unofficial ways keep getting shut down (Chamberlain blocked them in late
2023). But, I also had an extra Android phone that stays plugged in at
home.

The extra phone is the bridge.

So, I built **Garage Agent**. It is a small, free app for that Android
phone. It runs the official myQ app where no one can see it, and it shows
up in Apple Home as a garage door. Tap the door on your iPhone's lock
screen, and the Android phone presses the button in myQ for you. Then it
tells your iPhone what the door actually did.

No new hardware. No subscription. No computer. No typing commands.

## What you need

- **An extra Android phone** (Android 13 or newer) that can stay plugged
  in at home, on your Wi-Fi. You can keep using it for other things; the
  app never takes over its screen.
- **The myQ app** on that phone, signed in to your account.
- **An iPhone** with iOS 18 or newer, for the button on the lock screen.
- **An Apple TV or HomePod** at home, if you want the button to work
  when you are away (Apple calls this a Home hub).

## Set it up

It takes about fifteen minutes. Every step happens on one of your two
phones.

**On the Android phone**

1. **Download Garage Agent.** On the Android phone, open the
   [latest release](https://github.com/bhwilkoff/GarageDoor/releases/latest)
   and tap `garage-agent-1.0.apk` (the version number may be higher).
   Android will ask whether your browser may install apps; allow it. That
   is how apps from outside the Play Store get installed. Then tap
   **Install**.
2. **Open Garage Agent and work down the checklist.** Every item says
   what it is, why it matters, and has one button that does the work.
   Keep going until every item says **Done**. Two of them need a little
   patience:
   - **Wireless debugging** lives in a hidden menu called Developer
     options. The app tells you how to unhide it: tap **Build number**
     seven times. (Really.)
   - **Pair the agent** asks for a six-digit code from your phone's
     settings. Garage Agent asks for it in a notification, so you never
     have to leave the screen that shows the code. If Do Not Disturb is
     on, the notification will not pop up; swipe down from the top of
     the screen to find it.
3. **Turn off auto-update for myQ.** In the Play Store, open myQ, tap
   **⋮**, and uncheck **Enable auto update**. A surprise redesign of myQ
   is the most likely thing to break this, so you decide when it updates.

**On the iPhone**

4. **Add the door to Apple Home.** Keep the iPhone on the same Wi-Fi as
   the Android phone. In the **Home** app, tap **+**, then **Add
   Accessory**, then **More options...**, then **Garage Door**. Tap
   **Add Anyway** and enter the code shown at the top of Garage Agent.
5. **Put it on your lock screen.** Long-press the lock screen, tap
   **Customize**, then **Lock Screen**. Tap one of the two buttons at the
   bottom, choose **Home**, then **Accessory**, and pick your garage door.

When the top of Garage Agent says **Everything is working**, you can put
the Android phone down and forget about it.

<details>
<summary><strong>Show me every iPhone screen</strong></summary>

**Adding the door to Home**

1. Open the **Home** app and tap **+** (top right), then **Add
   Accessory**. A camera opens, asking you to scan a code.
2. Don't scan anything. Tap **More options...** under the camera. A list
   of nearby accessories appears.
3. Tap **Garage Door**.
4. iOS warns that this is an **Uncertified Accessory**. That is true: I
   built it, Apple did not certify it. Tap **Add Anyway**.
5. Enter the eight-digit setup code from the top of Garage Agent on the
   Android phone. (It only shows the code until a Home is paired.)
6. Pick a room, keep or change the name, and you are done. The door shows
   its real state in the Home app: Open, Closed, Opening, or Closing.

**The lock screen**

Long-press the lock screen and tap **Customize**, then **Lock Screen**.
Remove one of the two buttons at the bottom (tap **−**), tap the empty
spot (**+**), search for **Home**, choose **Accessory**, and pick the
garage door. Tap **Done**.

**Control Center**

Swipe down from the top right, long-press an empty spot, tap **Add a
Control**, search for **Home**, choose **Accessory**, and pick the garage
door.

Opening a garage door from the lock screen asks for Face ID first. Apple
treats garage doors like locks, and I am glad it does. Siri works too:
"Hey Siri, open the garage door" or "Is the garage door open?"

**Sharing it with your family**

In the Home app, tap **...** (top right), then **Home Settings**, then
**Invite People**, and invite them by their Apple Account. Once they
accept, the garage door is in their Home app too, and they can put it on
their own lock screen. Nothing changes on the Android phone.

</details>

## Keeping it working

- **After the Android phone restarts, unlock it once.** Android keeps
  every app locked until the first unlock, including Garage Agent and
  your myQ sign-in. About a minute after you unlock it, everything comes
  back on its own. Until then, Home shows the door as "No Response."
- **Keep it plugged in and on your home Wi-Fi.**
- **Don't sign out of myQ** on that phone.

## When something goes wrong

Open Garage Agent on the Android phone. The line at the top tells you the
next thing to fix, and most things fix themselves within a minute or two.

<details>
<summary><strong>Common problems and what to do</strong></summary>

| What you see | What is probably going on | What to do |
|---|---|---|
| "No Response" right after the Android phone restarted | It hasn't been unlocked yet | Unlock it once |
| "No Response" all the time | myQ signed out, or a myQ update changed its screens | Open Garage Agent; it names the problem. Open myQ to sign back in |
| The button works at home but not away | No Home hub, or it is off | In the Home app: **...** > **Home Settings** > **Home Hubs & Bridges** |
| **Garage Door** isn't in the list under More options | The iPhone is on a different Wi-Fi (or a guest network), or the door is already in a Home | Put both phones on the same Wi-Fi. If Garage Agent shows no code, it is already paired: long-press **Reset Apple Home** in Garage Agent and try again |
| Home says the code is wrong | A typo, or an earlier attempt got stuck | Check the code and try again. Still stuck: long-press **Reset Apple Home** in Garage Agent |
| An old **Garage Door** says No Response next to a working one | Left over from reinstalling | Long-press it, then **Remove Accessory** |

</details>

**Still stuck?** In Garage Agent, tap **Share report**. It writes a
plain-text summary of everything it checks, with no passwords and no
setup code. Paste it into a
[new issue](https://github.com/bhwilkoff/GarageDoor/issues/new?template=problem.md)
and tell me what happened.

**Updates.** Garage Agent does not update itself. Check the
[releases page](https://github.com/bhwilkoff/GarageDoor/releases) now and
then, or install the free [Obtainium](https://github.com/ImranR98/Obtainium)
app and give it this page's address; it will tell you when there is a new
version.

## A few honest limits

- **I have tested it on one phone,** a Pixel 8a. Other Android phones
  should work. I just haven't seen it yet.
- **One door per myQ account,** and the myQ app needs to be in English.
- **It is not in the Play Store.** Google doesn't allow apps that operate
  other apps this way, so it lives here, signed by me, and free.
- **It asks for a lot of trust, so here is exactly what it does.** To
  restart itself after the phone reboots, Garage Agent pairs with your
  phone's own Wireless debugging, the same way a computer would. That
  gives it deep access to that one phone, and nothing else. The key it
  uses is created on the phone and never leaves it, and you can remove it
  any time (it shows up as `garage-agent` in Wireless debugging). It
  never sees your myQ password. Only the people in your Apple Home can
  open the door.

## Why it works the way it does

I want tools that take the mechanical work off my hands and leave the
deciding to me. So, the door never moves unless someone asks it to. The
app always shows what it knows about the door, and when it learned it.
And when something breaks, it says so plainly, and tells you what to do
next.

Garage Agent is one of the apps built with the
[Human Shaped](https://humanshaped.org) method.

If you have an extra Android phone sitting in a drawer, it might be the
best garage door opener you own. Give it a try, and tell me how it goes.

---

*Want to see how it works, or build it yourself? Everything for builders
is in [`android-agent/`](android-agent/README.md).*
