# BLOFY – Buffer Free Auto Publishing

This setup uses **Buffer's free GraphQL API** + **GitHub Actions**, without any paid publishing subscription.

## First-time setup (required)

1. Buffer → **Settings → API** → **Create API key**.
2. Open [BLOFY-PLAYER-NEXT repository secrets](https://github.com/i20sss20-maker/BLOFY-PLAYER-NEXT/settings/secrets/actions) → **New repository secret**.
3. Set **Name:** `BUFFER_API_KEY` and **Secret:** paste the key from Buffer. Do not place it in code, issues, public chat or commits.
4. In [GitHub Actions](https://github.com/i20sss20-maker/BLOFY-PLAYER-NEXT/actions/workflows/blofy-buffer-auto-post.yml), choose **Run workflow** with `check` to verify the connections. Review the logs.
5. First X and Instagram test posts were already created on 2026-11-12. Do not run either one-time mode again unless you want an additional post. Scheduled runs continue automatically.

## Publishing schedule (Saudi Arabia)

- **X @i20au:** eight posts/day, approximately every two hours, at 09:30, 11:30, 13:30, 15:30, 17:30, 19:30, 21:30, 23:30 (timing may vary because GitHub scheduled workflows can be delayed). Each text is varied using a content bank.
- **Instagram i10s01:** one image post a day around 18:00 Saudi time. Verified current connection is a **personal account**, so Buffer schedules a **mobile notification**; a person must tap to finish publishing. For fully automatic posting, change Instagram to a professional account and reconnect if needed.
- **TikTok i.20as:** one image post per day beginning **2026-11-28**; this avoids overlapping the existing scheduled TikTok posts in Metricool through November 27.
- Current image assets are hosted by Metricool, with original artwork in `marketing/2026-11/`.

Buffer's free account holds at most **10 scheduled posts per channel**. This job does not create a post when the channel queue has 9 or more pending items, and it checks the API before creating anything. The account/API also has usage limits. If the platform rejects a media post, see GitHub Actions logs.

## Safety

- The automation selects only the matching X username `i20au` and TikTok account `i.20as`; it avoids guessing among multiple Instagram channels.
- The repository contains no Buffer token.
- Never promise copyrighted channels, specific film libraries, guarantees of availability, or licensed content unless independently verified.
- `post-x-now`, `post-instagram-now`, and scheduled jobs are **real publishing operations**, not simulations. `check` reads only.

## Files

- `content.json`: approved advertising copy and hosted poster URL list
- `publish.py`: API connection, account lookup, duplicate checks and scheduling
- `.github/workflows/blofy-buffer-auto-post.yml`: GitHub Actions scheduler

For Buffer documentation: https://developers.buffer.com/guides/getting-started.html
