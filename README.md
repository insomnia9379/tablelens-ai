# Casino Coach (Android)

Android casino helper, the phone version of the Windows Casino Coach. A floating **SCAN** button sits over your game; tap it on your turn and Claude reads the screen, works out which game it is, and shows the best move with a one-line reason.

Games: blackjack (HIT / STAND / DOUBLE / SPLIT), video poker (which cards to HOLD), Texas Hold'em and other poker (FOLD / CHECK / CALL / RAISE), table poker games (Three Card, Ultimate Texas Hold'em, Let It Ride, Pai Gow…), baccarat, roulette, craps, keno and slots (lowest-edge bets, bonus and gamble choices).

## How it works

1. `MainActivity` stores your Claude API key on the phone and asks for screen-capture and "display over other apps" permission.
2. `ScanService` keeps the capture session open but only takes a picture when you tap SCAN. It hides the panel, grabs one frame, and resizes it to at most 1568 px.
3. `ClaudeAdvisor` sends that frame to `claude-opus-5-5` (low effort for speed) with optimal-strategy instructions for each game, and gets back a small JSON answer: game, move, what it read, reason, confidence.

Works with the phone upright or sideways and in any browser or app, because Claude reads the whole picture instead of fixed screen spots.

## Setup

1. Create an API key at console.anthropic.com and add a few dollars of credit. Each scan costs roughly a cent.
2. Install the APK from the latest **Build APK** run (Actions tab, artifact `casino-coach-debug`).
3. Open Casino Coach, paste the key, tap **SAVE KEY**, then **START**. Allow "display over other apps" and choose **Entire screen** when Android asks what to share.

## Notes

- The key is stored only on your phone, in the app's private storage.
- Screenshots go to Anthropic's API only when you tap SCAN.
- Check the casino's terms before using it on a real-money or sweepstakes account. Many forbid software-assisted play.
