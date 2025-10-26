[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/5584/badge)](https://www.bestpractices.dev/projects/5584)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/ben-manes/caffeine/badge)](https://scorecard.dev/viewer/?uri=github.com/ben-manes/caffeine)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=com.github.ben-manes.caffeine%3Acaffeine&metric=security_rating)](https://sonarcloud.io/summary/overall?id=com.github.ben-manes.caffeine%3Acaffeine)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/a05a489421fa4be99a29cea8cb37b476)](https://app.codacy.com/gh/ben-manes/caffeine/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)
[![Reproducible Builds](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/jvm-repo-rebuild/reproducible-central/master/content/com/github/ben-manes/caffeine/badge.json)](https://github.com/jvm-repo-rebuild/reproducible-central/blob/master/content/com/github/ben-manes/caffeine/README.md)
[![codecov](https://codecov.io/github/ben-manes/caffeine/branch/master/graph/badge.svg?token=bOBWqhGbOP)](https://codecov.io/github/ben-manes/caffeine)

# Reporting Security Issues

If you discover a security issue, please use GitHub's mechanism for [privately reporting a vulnerability][].
Under the main repository's [security tab][], click "Report a vulnerability" to open the advisory form.

Thanks for helping make everyone safer.

[privately reporting a vulnerability]: https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability#privately-reporting-a-security-vulnerability
[security tab]: https://github.com/ben-manes/caffeine/security

# Signature Validation
All artifacts are published on the Maven Central repository accompanied by an `*.asc` GPG and
`*.sigstore.json` Sigstore files.

```
pub   rsa2048 2019-11-10 [SC]
      635E E627 345F 3C1D D422  B2E2 07D3 5168 20BC F6B1
uid           [ unknown] Ben Manes <ben.manes@gmail.com>
sub   rsa2048 2019-11-10 [E]
```

<details>
<summary>GPG Key</summary>

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQENBF3HgdMBCAC3ET5ipFXdZ9GGMbtsCQ3HGT40saajsNDOdov2nMJxzKkVe3wk
sN3bpgbsqBU9ykVkIhX8zV5+v8DOBzkV0pJ2eLjFa9jBPvNjV+KoK2BAI5pzNzYg
sHPwo1aRXdI0MvCy+7iaIiiGF4/O16AhU4LmALHnaRQZCyuN6VOQ8rlqNvcczwUf
J2DQeLHqR/tsch7S01hGpPAptBeu19PyAlQsntYN0yLCLKoe9dFXWCDkvd1So5LF
6So+ryPqupumBbh4WxCmTp9qwDJYJItjAE0zyPe890FurOtxrFTwtRtX6d6qGKkY
/B4T3r0tTE1EiOUpmSnxmGNItMh7/l5UtnHjABEBAAG0H0JlbiBNYW5lcyA8YmVu
Lm1hbmVzQGdtYWlsLmNvbT6JAU4EEwEIADgWIQRjXuYnNF88HdQisuIH01FoILz2
sQUCXceB0wIbAwULCQgHAgYVCgkICwIEFgIDAQIeAQIXgAAKCRAH01FoILz2sdoo
B/0YUh73jUMl14MjWvp9zrFHN8h+LqB4NMQcP93RdPTtDKi0a+0h8gQtm0D+K49Q
BQbFztOObfZS3kdJ3VOqmodScWrGtMU3HsYT2ioQalqbYvl9FIPDrlOjHaZgwgyJ
We0DVKHRApbtIh+NxTpQUJtanxgF60ZtOoToZe8XMGc9LaCZcrFxK/AlMdDMgUCx
qzBbXhAcvut2bJVL5B4kLNMABrbUuFMjTNI4JxvgTXKL/jNk6XPtCjdmgIh7mT/G
Mpu9t3i1zegAPdM5N/MAgiGHqm+blANLniSAbZja8Ny7211fwOYoJ546VPwDjL7B
rBlymB3COoYZhql2DcBBg39cuQENBF3HgdMBCACu3VQKKmagcPbcMZOqbDXE5iK3
0G742rCpf/j3ywnwTZJQ/58HtAi8+/fXxUhTHswoON2TwiiHrHAkObe+K9A+jv0E
xjKVMmQ/sOCYWZDEGMth4yJnzDbT1Tlm/l2i5Lv0ZaD7fTEhtprQNuU06dveTeJs
zDyqtK9T80mvI4+GH59wM80l1y6uj8KA4pY0PdSFgbyS9iAFADGsUsc6t1KiZ5W1
9odMjDPlQtJ20pm5CvJlDZbYNRJ54CSldZikRvmNRg5mWdRLNfbRMFDLFfcdYLdO
WJXnAt9cKFJC9P//ItZFrlhu3akTH//HF2kxQNW61Sd92/xtFUD/2tN1GlXfABEB
AAGJATYEGAEIACAWIQRjXuYnNF88HdQisuIH01FoILz2sQUCXceB0wIbDAAKCRAH
01FoILz2saySCACibIpnls5wJkfX1B/7tDjWk2hEGZYcASr0xp/DDwSgJ5edByuQ
NQF7RHuCk0ke6IQGfytMLJlXeEIu79DvgPakxBP5iG+c095FbhRu+9nCEkRqQvop
4fA7ZdhuerOyuObWz8+o3Z2RywWPXlK+F/9iJiO/qtvmdORuikJtN9VxgvAUvANZ
RtlzjL296p0TJzGqXhyer46CHl/Yj7TtX6EpnZDgiaQbOWRFOZ5x81xI79bQD7Ew
DzfrwQHbjQDkqhkwOoV6Wq239ZaHh6p7GXHnQkDMQ0H/7Y2tw6PH5VM8fDJkJKF2
PIukJrUXa06KqrdZ9YxqvSmu5UY6tMSRwGWp
=/wFN
-----END PGP PUBLIC KEY BLOCK-----
```
