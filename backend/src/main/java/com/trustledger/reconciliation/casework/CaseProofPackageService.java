package com.trustledger.reconciliation.casework;

import com.trustledger.app.EvidenceService;
import com.trustledger.evidence.Checksums;
import com.trustledger.evidence.EvidenceStorage;
import com.trustledger.persistence.entity.EvidenceExportEntity;
import com.trustledger.security.ConflictException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Packages one stored case export with the exact source bytes named by its manifests. */
@Service
public class CaseProofPackageService {

    private static final long MAX_PACKAGE_INPUT_BYTES = 100L * 1024 * 1024;
    private final EvidenceService evidence;
    private final EvidenceStorage storage;
    private final CaseworkStore casework;
    private final ObjectMapper json;

    public CaseProofPackageService(EvidenceService evidence, EvidenceStorage storage,
                                   CaseworkStore casework, ObjectMapper json) {
        this.evidence = evidence;
        this.storage = storage;
        this.casework = casework;
        this.json = json;
    }

    public byte[] packageExport(UUID tenantId, UUID userId, UUID exportId) {
        EvidenceExportEntity export = evidence.requireExportInScope(tenantId, userId, exportId);
        if (!"RECONCILIATION_CASE".equals(export.getResourceType())) {
            throw new IllegalArgumentException("Only reconciliation case exports have source proof packages");
        }
        byte[] bundle;
        try {
            bundle = evidence.download(tenantId, userId, exportId);
        } catch (IllegalArgumentException missing) {
            throw new ConflictException("stored case export bytes are unavailable: " + exportId);
        }
        if (!Checksums.sha256(bundle).equals(export.getChecksum())) {
            throw new ConflictException("stored case export no longer matches its checksum");
        }
        if (bundle.length > MAX_PACKAGE_INPUT_BYTES) {
            throw new ConflictException("proof package exceeds the 100 MiB source limit");
        }
        JsonNode document = json.readTree(bundle);
        JsonNode sources = document.path("content").path("sources");
        if (!sources.isArray()) throw new ConflictException("case export has no source manifest");
        Set<String> included = new HashSet<>();
        long inputBytes = bundle.length;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "bundle.json", bundle);
            for (JsonNode source : sources) {
                UUID importId = UUID.fromString(source.path("importId").asString());
                CaseworkStore.ImportRow manifest = casework.findImport(tenantId, export.getResourceId(), importId)
                    .orElseThrow(() -> new ConflictException("source import is no longer available: " + importId));
                String digest = source.path("fileSha256").asString();
                if (!digest.equals(manifest.fileSha256())
                    || !source.path("evidenceStorageKey").asString().equals(manifest.storageKey())
                    || source.path("byteSize").asLong() != manifest.byteSize()) {
                    throw new ConflictException("source manifest changed since this export: " + importId);
                }
                if (!digest.matches("[0-9a-f]{64}")) throw new ConflictException("invalid source digest: " + importId);
                if (!included.add(digest)) continue;
                inputBytes += manifest.byteSize();
                if (inputBytes > MAX_PACKAGE_INPUT_BYTES) {
                    throw new ConflictException("proof package exceeds the 100 MiB source limit");
                }
                byte[] original;
                try {
                    original = storage.retrieve(manifest.storageKey());
                } catch (IllegalArgumentException missing) {
                    throw new ConflictException("source bytes are unavailable: " + importId);
                }
                if (original.length != manifest.byteSize()
                    || !Checksums.sha256(original).equals("sha256:" + digest)) {
                    throw new ConflictException("source bytes no longer match the manifest: " + importId);
                }
                add(zip, "sources/" + digest, original);
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not create proof package", e);
        }
    }

    private static void add(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}
