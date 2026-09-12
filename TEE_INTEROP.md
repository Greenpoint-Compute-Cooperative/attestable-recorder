# TEE Interop Integration

This document describes how to integrate attestable recordings with the [TEE Interop](https://teleport-computer.github.io/tee-interop/) decentralized verification network.

## Overview

TEE Interop provides a **smart contract-based registry** where different TEE platforms (Intel SGX, AMD SEV, Google Titan M2, etc.) can verify each other without a central authority.

Instead of trusting a single Warden server, attestations are:
1. Submitted to an on-chain verifier contract
2. Validated by platform-specific verification logic
3. Registered in a decentralized network
4. Verifiable by anyone with access to the blockchain

## Architecture

```
┌─────────────────────────────────┐
│  GrapheneOS Phone               │
│  (Titan M2 attestation)         │
└─────────────────────────────────┘
           ↓
┌─────────────────────────────────┐
│  TEE Interop Smart Contract     │
│  ┌───────────────────────────┐  │
│  │ IVerifier (Titan M2)      │  │
│  │ - Verify cert chain       │  │
│  │ - Check Google root CA    │  │
│  │ - Validate StrongBox      │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
           ↓
┌─────────────────────────────────┐
│  On-Chain Registry              │
│  - Device identity              │
│  - Public key                   │
│  - Attestation timestamp        │
└─────────────────────────────────┘
```

## Implementation Steps

### 1. Deploy Titan M2 Verifier Contract

Create a Solidity contract implementing `IVerifier` for Android Key Attestation:

```solidity
// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

interface IVerifier {
    function verify(bytes calldata attestation) external returns (bool);
}

contract TitanM2Verifier is IVerifier {
    // Google hardware attestation root CA (Pixel devices)
    bytes32 public constant GOOGLE_ROOT_CA_HASH = 0x...;

    function verify(bytes calldata attestation) external override returns (bool) {
        // Parse attestation certificate chain
        // Verify signature chain up to Google root
        // Check StrongBox security level
        // Validate device state

        // Implementation requires:
        // 1. X.509 certificate parsing
        // 2. ECDSA signature verification
        // 3. ASN.1 decoding for attestation extension

        return true; // if valid
    }
}
```

### 2. Register Attestation On-Chain

Add on-chain registration to the Android app:

```kotlin
class TeeInteropRegistrar(
    private val web3Provider: Web3Provider,
    private val contractAddress: String
) {
    suspend fun registerAttestation(
        attestationChain: Array<Certificate>,
        publicKey: ByteArray
    ): TransactionReceipt {
        // Encode attestation for on-chain verification
        val attestationBytes = encodeAttestationChain(attestationChain)

        // Submit to TEE Interop registry
        val tx = registryContract.register(attestationBytes, publicKey)

        return tx.await()
    }
}
```

### 3. Verify Against On-Chain Registry

Anyone can verify a recording by:
1. Fetching the public key from on-chain registry
2. Verifying it matches the recording's attestation
3. Checking signatures on audio chunks

```kotlin
class OnChainVerifier(
    private val web3Provider: Web3Provider,
    private val registryAddress: String
) {
    suspend fun verifyRecording(
        manifest: RecordingManifest,
        chunks: List<AudioChunk>
    ): Boolean {
        // Extract public key from manifest
        val publicKey = extractPublicKey(manifest.attestationChain)

        // Check on-chain registry
        val isRegistered = registryContract.isRegistered(publicKey)
        if (!isRegistered) return false

        // Verify chunk signatures
        return chunks.all { chunk ->
            verifySignature(publicKey, chunk.data, chunk.signature)
        }
    }
}
```

## Benefits

1. **No Single Point of Trust**: Don't rely on one verifier server
2. **Public Auditability**: Anyone can verify attestations
3. **Cross-Platform**: Works with other TEEs (SGX, SEV, etc.)
4. **Immutable Record**: Attestations recorded on-chain
5. **Decentralized Infrastructure**: No operator dependency

## Challenges

### On-Chain Certificate Verification

X.509 certificate parsing and ECDSA verification in Solidity is expensive:
- Gas costs for certificate chain validation
- Need to optimize ASN.1 parsing
- Consider using ZK proofs to compress verification

### Alternatives

1. **Hybrid Approach**:
   - Verify attestation off-chain (Warden)
   - Submit verification proof on-chain
   - Use optimistic rollups for disputes

2. **ZK-SNARKs**:
   - Generate ZK proof that attestation is valid
   - Submit compact proof on-chain
   - Much cheaper gas costs

3. **Attestation Oracles**:
   - Multiple independent Warden servers
   - Sign verification results
   - Multi-sig threshold on-chain

## Example: TEE Interop Flow

```
1. Phone generates key in Titan M2
   ↓
2. Attestation sent to smart contract
   ↓
3. TitanM2Verifier validates cert chain
   ↓
4. Registry stores: (device_id, public_key, timestamp)
   ↓
5. Recording is made and signed
   ↓
6. Verifier checks on-chain registry
   ↓
7. Public can verify without trusting server
```

## Network Support

TEE Interop can run on various chains:
- Ethereum (expensive)
- Polygon (cheaper)
- Optimism/Arbitrum (L2s)
- Custom app-chains (Cosmos, Substrate)

## Next Steps

To implement full TEE Interop support:

1. [ ] Implement Solidity verifier for Android attestation
2. [ ] Add web3 dependencies to Android app
3. [ ] Create on-chain registration flow
4. [ ] Optimize gas costs (batching, compression)
5. [ ] Add dispute resolution mechanism
6. [ ] Deploy to testnet (Sepolia, Mumbai)
7. [ ] Create decentralized verifier UI

## Resources

- [TEE Interop Docs](https://teleport-computer.github.io/tee-interop/)
- [Android Key Attestation Extension](https://developer.android.com/privacy-and-security/security-key-attestation#certificate_schema)
- [Solidity X.509 Libraries](https://github.com/noot/solidity-certificate-verification)
- [ZK Attestation](https://github.com/privacy-scaling-explorations/zk-attestation)

## Security Considerations

- **Front-running**: Attestations could be registered by adversaries
- **Replay Attacks**: Include nonces in attestation challenges
- **Private Keys**: Never expose device private keys
- **Revocation**: Need mechanism to revoke compromised devices
- **Smart Contract Bugs**: Audits critical for verifier logic
