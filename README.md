# TableLens AI

Android blackjack helper. A floating **SCAN** button sits over your game; tap it on your turn and Claude reads the table from a screenshot and shows the move (HIT / STAND / DOUBLE / SPLIT) with a one-line reason.

## How it works

1. `MainActivity` stores your Claude API key on the phone and asks for screen-capture and "display over other apps" permission.
2. `ScanService` keeps the capture session open but only takes a picture when you tap SCAN. It hides the panel, grabs one frame, and resizes it to at most 1568 px.
3. `ClaudeAdvisor` sends that frame to `claude-opus-5-5` (low effort for speed) with the table rules shown on the felt (dealer hits soft 17, blackjack 3:2) and gets back a small JSON answer.

Works with the phone upright or sideways and in any browser or app, because Claude reads the whole picture instead of fixed screen spots.

## Setup

1. Create an API key at console.anthropic.com and add a few dollars of credit. Each scan costs roughly a cent.
2. Install the APK from the latest **Build APK** run (Actions tab, artifact `tablelens-ai-debug`).
3. Open TableLens AI, paste the key, tap **SAVE KEY**, then **START**. Allow "display over other apps" and choose **Entire screen** when Android asks what to share.

## Notes

- The key is stored only on your phone, in the app's private storage.
- Screenshots go to Anthropic's API only when you tap SCAN.
- Check the casino's terms before using it on a real-money or sweepstakes account. Many forbid software-assisted play.
