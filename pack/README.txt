Zone Watch - Software Engineering Assessment
Candidate pack, version 1.1

Read the Candidate Brief (PDF) first. This pack holds the material it refers to.

Files
  feed.ndjson.gz            Recorded position feed: about 226,000 lines for 3,000 assets over
                            12 minutes, in delivery order. One JSON document per line.
  zones.geojson             The zones, as received from the upstream team (GeoJSON, WGS84).
  openapi.yaml              The fixed API contract. Automated checks call these endpoints.
  replay.py                 Delivers the feed to your ingest endpoint over parallel connections.
                            Python 3.9 or newer, standard library only. Run: python3 replay.py --help
  seed-users.sample.json    Users your system must be seeded with (one feed account, one
                            supervisor, twenty operators). Sample passwords for local use.

Four things to know
  1. The feed and the zones are deliberately imperfect, like real upstream data.
     Finding the problems and deciding what to do about them is part of the assessment.
  2. We do not build or run your source code. We load your offline bundle on an isolated
     Linux x86_64 machine with no internet access and check it through the same contract.
  3. Treat the users file as a deploy-time secret: mount it or pass it at start-up. Do not
     commit passwords to your repository and do not bake them into an image.
  4. The interview is on your own laptop with your screen shared.

Before you submit (brief, sections 7 and 11)
  [ ] Public GitHub repository with a neutral name, full history, tags v1.0.0 and v1.1.0.
  [ ] Release v1.1.0 holds the offline bundle and a file named SHA256SUMS that lists every
      other release file. Each file is smaller than 2 GB.
  [ ] Every image is linux/amd64:
        docker image inspect IMAGE --format '{{.Os}}/{{.Architecture}}'
  [ ] INSTALL.md uses only docker, docker compose, tar, gzip, cat and sha256sum.
  [ ] You removed your local images, switched the network off, followed your own INSTALL.md
      with a different users file, and the system started and worked.

Quick start once your system is running
  export FEED_PASSWORD='<password of the feed user>'
  python3 replay.py --base-url http://localhost:8080 --speed 1      # real time, about 300 lines/s
  python3 replay.py --base-url http://localhost:8080 --speed 5      # the UI must stay smooth here
  python3 replay.py --base-url http://localhost:8080 --speed max    # throughput target: 2,000 lines/s

The scenario, the assets, the zones and the data are fictional.
