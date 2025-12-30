# 🔌 API Endpoints Reference

> **Source of truth for REST API contracts.**

This document describes the REST API endpoints exposed by the JorManager backend.

---

## API Overview

| Base URL | Environment |
|----------|-------------|
| `http://localhost:8787` | Local Development |

### Server Configuration

```properties
server.address=127.0.0.1
server.port=8787
```

---

## Endpoint Categories

| Category | Base Path | Controller | Description |
|----------|-----------|------------|-------------|
| **Hosts** | `/api/hosts` | `HostController` | SSH host management |
| **Nodes** | `/api/nodes` | `NodeController` | Cardano node operations |
| **Blocks** | `/api/blocks` | `BlockController` | Block monitoring, leader logs |
| **Wallet** | `/api/wallet` | `WalletController` | Wallet and transaction management |
| **Files** | `/api/files` | `FileController` | Remote file operations |

---

## Host Endpoints

Manage SSH connections to remote servers.

### GET `/api/hosts`
List all configured hosts.

### POST `/api/hosts`
Create a new host connection.

### PUT `/api/hosts/{id}`
Update host configuration.

### DELETE `/api/hosts/{id}`
Remove a host.

### POST `/api/hosts/{id}/test`
Test SSH connection to host.

---

## Node Endpoints

Manage Cardano nodes running on hosts.

### GET `/api/nodes`
List all nodes.

### GET `/api/nodes/{id}`
Get node details and status.

### POST `/api/nodes`
Create/register a new node.

### POST `/api/nodes/{id}/restart`
Restart a node via SSH.

### POST `/api/nodes/{id}/rotate-kes`
Rotate KES keys for a node.

### GET `/api/nodes/{id}/status`
Get current node synchronization status.

---

## Block Endpoints

Block production monitoring and leader log calculation.

### GET `/api/blocks`
List produced blocks.

### GET `/api/blocks/leader-logs`
Get upcoming leader slots for a pool.

### POST `/api/blocks/validate`
Validate block production.

---

## Wallet Endpoints

Wallet management and transaction operations.

### GET `/api/wallet/entries`
List wallet entries.

### POST `/api/wallet/entries`
Create a new wallet entry.

### GET `/api/wallet/balance`
Get wallet balance.

### POST `/api/wallet/send`
Send ADA transaction.

### POST `/api/wallet/claim-rewards`
Claim staking rewards.

---

## File Endpoints

Remote file operations via SSH.

### GET `/api/files`
List files in a directory on a host.

### GET `/api/files/download`
Download file from remote host.

### POST `/api/files/upload`
Upload file to remote host.

---

## WebSocket Endpoints

Real-time updates via STOMP over WebSocket.

| Endpoint | Purpose |
|----------|---------|
| `/ws` | WebSocket connection endpoint |
| `/topic/nodes` | Node status updates |
| `/topic/blocks` | New block notifications |

---

## Error Handling

Errors are returned with appropriate HTTP status codes and error details.

### Common HTTP Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 400 | Bad Request (validation error) |
| 404 | Resource not found |
| 500 | Internal server error |

---

## Notes

- All endpoints accept and return JSON
- WebSocket used for real-time dashboard updates
- SSH operations may have longer timeouts
