# Object storage identities (local development)

`s3.json` is mounted read-only into the `oriah-s3` container and read by
SeaweedFS's S3 gateway at startup (`-s3.config=/etc/seaweedfs/s3.json`).

## Why there are two identities and not one

**`admin`** can create buckets. **`catalog`** can only read, write and list
inside `oriah-catalog` and *cannot create a bucket* — which was measured
against the server rather than assumed: the scoped key receives `403` on a
bucket create and only the admin identity succeeds.

That split is the reason the stack has a one-shot `oriah-s3-init` container.
Bucket creation has to happen with the admin credentials, so catalog-service
can only *check* that its bucket exists — and it fails startup when it does
not, rather than silently accepting uploads into storage that is not there.

The practical consequence: a compromise of catalog-service cannot create,
replace or destroy storage locations. It can only touch objects inside the one
bucket it was given.

## Why the file exists at all

The AWS SDK **always signs** its requests. SeaweedFS answers a signed request
with `400 "Signed request requires setting up SeaweedFS S3 authentication"`
when it has no identities configured — even though it accepts *anonymous*
requests happily in that state. So "no credentials configured" is not a
working mode for an SDK client; there is no way to run without this file.

That cost a debugging cycle: a hand-written SigV4 probe used during the
storage evaluation mounted an identity file, so the requirement was invisible
until the real SDK ran against a container that had none, and every operation
failed at once.

## These credentials are development-only

They sit in the repository next to `SPRING_DATASOURCE_PASSWORD: catalog` and
the Keycloak `admin/admin` bootstrap, at the same hygiene level as the rest of
the local stack. They differ from those in one way worth noting: the admin key
can create and delete buckets, so do not reuse this file outside local
development. A real deployment supplies both identities from its secret store
and never commits them.

## Why SeaweedFS and not MinIO

The products phase was planned against MinIO. Its server and client images
have been withdrawn from Docker Hub — the `minio/` namespace still publishes
twenty repositories, but `minio/minio` and `minio/mc` are not among them, an
anonymous pull token comes back with an empty access claim, and an
*authenticated* pull fails identically. `quay.io/minio/minio` returns 401.
(A control pull of `busybox` in the same shell succeeded, so this is not a
network or rate-limit artifact.) `testcontainers-minio` is unusable for the
same reason, which is why the S3 integration test drives a plain
`GenericContainer`.

Catalog talks to the S3 API through the AWS SDK, so the server is a
configuration choice: MinIO remains a drop-in via
`oriah.catalog.storage.endpoint` if its images become available again, and
nothing in the data model depends on which server is running — `product_images`
stores an opaque key, never a URL.
