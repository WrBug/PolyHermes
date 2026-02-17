#!/usr/bin/env node

/**
 * 将 Wrapped Collateral (WCOL) 解包为 USDC.e
 *
 * Polygon 上 Polymarket 使用 USDC.e（Bridged USDC），解包后到账的为 USDC.e，在钱包/区块浏览器中显示为 USDC.e。
 *
 * 合约: Polymarket Neg Risk WrappedCollateral
 * Polygon: 0x3A3BD7bb9528E159577F7C2e685CC81A765002E2
 * 方法: unwrap(address _to, uint256 _amount)
 *
 * 使用方式:
 *   1) 从 Safe 解包（Gasless，需 Builder 凭证，主钱包无需 POL）:
 *      PRIVATE_KEY=0x... SAFE_ADDRESS=0xB4c3... BUILDERS_API_KEY=... BUILDERS_SECRET=... BUILDERS_PASSPHRASE=... node scripts/unwrap-wcol.js
 *   2) 从 Safe 解包（自付 gas，主钱包需少量 POL）:
 *      PRIVATE_KEY=0x... SAFE_ADDRESS=0xB4c3... node scripts/unwrap-wcol.js
 *   3) 从 EOA 解包（WCOL 在主钱包上）:
 *      PRIVATE_KEY=0x... node scripts/unwrap-wcol.js
 *
 * 环境变量:
 *   PRIVATE_KEY  主钱包私钥（必需）
 *   SAFE_ADDRESS 若设置则从 Safe 执行 unwrap
 *   UNWRAP_TO_MAIN_WALLET=1  从 Safe 解包时，USDC.e 转到主钱包；不设则转到 Safe
 *   BUILDERS_API_KEY / BUILDERS_SECRET / BUILDERS_PASSPHRASE  三者齐备时走 Builder Relayer（Gasless，主钱包无需 POL）
 *   RPC_URL      Polygon RPC（可选）
 *   RELAYER_URL  Builder Relayer 地址（可选，默认 https://relayer-v2.polymarket.com）
 */

import { ethers } from "ethers";
import crypto from "crypto";

const CHAIN_ID = 137;
const RPC_URL = process.env.RPC_URL || "https://polygon-rpc.com";
const RELAYER_URL = (process.env.RELAYER_URL || "https://relayer-v2.polymarket.com").replace(/\/$/, "");
const WCOL_ADDRESS = "0x3A3BD7bb9528E159577F7C2e685CC81A765002E2";

const WCOL_ABI = [
  "function balanceOf(address account) view returns (uint256)",
  "function decimals() view returns (uint8)",
  "function unwrap(address _to, uint256 _amount)",
];

// Safe 合约：nonce()
const SAFE_ABI = ["function nonce() view returns (uint256)"];

function normalizePrivateKey(key) {
  if (!key || !key.trim()) return null;
  const k = key.trim();
  return k.startsWith("0x") ? k : "0x" + k;
}

function getPrivateKey() {
  const key = normalizePrivateKey(process.env.PRIVATE_KEY);
  if (!key) {
    console.error("请设置环境变量 PRIVATE_KEY（主钱包私钥）");
    process.exit(1);
  }
  return key;
}

// EIP-712: domain type hash for Safe (chainId + verifyingContract)
function getSafeDomainTypeHash() {
  const typeStr = "EIP712Domain(uint256 chainId,address verifyingContract)";
  return ethers.utils.keccak256(ethers.utils.toUtf8Bytes(typeStr));
}

function encodeSafeDomain(chainId, verifyingContract) {
  const typeHash = getSafeDomainTypeHash();
  const chainIdHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(chainId).toHexString(), 32);
  const contractHex = ethers.utils.hexZeroPad(verifyingContract.toLowerCase(), 32);
  return ethers.utils.keccak256(ethers.utils.concat([typeHash, chainIdHex, contractHex]));
}

// SafeTx type hash
function getSafeTxTypeHash() {
  const typeStr =
    "SafeTx(address to,uint256 value,bytes data,uint8 operation,uint256 safeTxGas,uint256 baseGas,uint256 gasPrice,address gasToken,address refundReceiver,uint256 nonce)";
  return ethers.utils.keccak256(ethers.utils.toUtf8Bytes(typeStr));
}

function encodeSafeTxMessage(to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, nonce) {
  const typeHash = getSafeTxTypeHash();
  const toHex = ethers.utils.hexZeroPad(to.toLowerCase(), 32);
  const valueHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(value).toHexString(), 32);
  const dataHash = data && data !== "0x" ? ethers.utils.keccak256(data) : ethers.constants.HashZero;
  const opHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(operation).toHexString(), 32);
  const safeTxGasHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(safeTxGas).toHexString(), 32);
  const baseGasHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(baseGas).toHexString(), 32);
  const gasPriceHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(gasPrice).toHexString(), 32);
  const gasTokenHex = ethers.utils.hexZeroPad(gasToken.toLowerCase(), 32);
  const refundHex = ethers.utils.hexZeroPad(refundReceiver.toLowerCase(), 32);
  const nonceHex = ethers.utils.hexZeroPad(ethers.BigNumber.from(nonce).toHexString(), 32);
  return ethers.utils.keccak256(
    ethers.utils.concat([
      typeHash,
      toHex,
      valueHex,
      dataHash,
      opHex,
      safeTxGasHex,
      baseGasHex,
      gasPriceHex,
      gasTokenHex,
      refundHex,
      nonceHex,
    ])
  );
}

function hashStructuredData(domainSeparator, messageHash) {
  const prefix = "0x1901";
  return ethers.utils.keccak256(ethers.utils.concat([prefix, domainSeparator, messageHash]));
}

// Gnosis Safe 签名：先对 structHash 做 personal_sign 风格（\x19Ethereum Signed Message:\n32 + hash），再对结果做 keccak256 后签名
async function signSafeTx(wallet, structHash) {
  const hashBytes = ethers.utils.arrayify(structHash);
  const messagePrefix = "\x19Ethereum Signed Message:\n32";
  const prefixed = ethers.utils.concat([
    ethers.utils.toUtf8Bytes(messagePrefix),
    hashBytes,
  ]);
  const hashToSign = ethers.utils.keccak256(prefixed);
  const sig = await wallet._signingKey().signDigest(ethers.utils.arrayify(hashToSign));
  let v = sig.v;
  if (v < 27) v += 27;
  const r = ethers.utils.hexZeroPad(ethers.BigNumber.from(sig.r).toHexString(), 32).slice(2);
  const s = ethers.utils.hexZeroPad(ethers.BigNumber.from(sig.s).toHexString(), 32).slice(2);
  const vByte = v.toString(16).padStart(2, "0");
  return "0x" + r + s + vByte;
}

// Builder Relayer 要求 Safe 签名使用调整后的 v（与后端 splitAndPackSig 一致），否则链上恢复签名者会失败
function packSafeSignatureForRelayer(signatureHex) {
  const raw = signatureHex.startsWith("0x") ? signatureHex.slice(2) : signatureHex;
  if (raw.length !== 130) throw new Error("签名长度应为 65 字节 (130 个十六进制字符)");
  const r = raw.slice(0, 64);
  const s = raw.slice(64, 128);
  const vHex = raw.slice(128, 130);
  let v = parseInt(vHex, 16);
  const adjustedV = v === 0 || v === 1 ? v + 31 : v === 27 || v === 28 ? v + 4 : v;
  return "0x" + r + s + adjustedV.toString(16).padStart(2, "0");
}

// 构建 execTransaction(to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, signatures)
function buildExecTransactionCalldata(to, data, operation, signatureHex) {
  const iface = new ethers.utils.Interface([
    "function execTransaction(address to, uint256 value, bytes data, uint8 operation, uint256 safeTxGas, uint256 baseGas, uint256 gasPrice, address gasToken, address refundReceiver, bytes signatures)",
  ]);
  const value = 0;
  const safeTxGas = 0;
  const baseGas = 0;
  const gasPrice = 0;
  const gasToken = ethers.constants.AddressZero;
  const refundReceiver = ethers.constants.AddressZero;
  const sigBytes = ethers.utils.arrayify(signatureHex);
  return iface.encodeFunctionData("execTransaction", [
    to,
    value,
    data,
    operation,
    safeTxGas,
    baseGas,
    gasPrice,
    gasToken,
    refundReceiver,
    sigBytes,
  ]);
}

function isBuildersConfigured() {
  const k = process.env.BUILDERS_API_KEY?.trim();
  const s = process.env.BUILDERS_SECRET?.trim();
  const p = process.env.BUILDERS_PASSPHRASE?.trim();
  return !!(k && s && p);
}

function buildBuilderSignature(signString, secret) {
  let decodedSecret;
  try {
    decodedSecret = Buffer.from(secret, "base64");
  } catch {
    try {
      decodedSecret = Buffer.from(secret.replace(/-/g, "+").replace(/_/g, "/"), "base64");
    } catch {
      decodedSecret = Buffer.from(secret, "utf8");
    }
  }
  const hmac = crypto.createHmac("sha256", decodedSecret);
  hmac.update(signString, "utf8");
  const base64 = hmac.digest("base64");
  return base64.replace(/\+/g, "-").replace(/\//g, "_");
}

function getBuilderHeaders(method, path, body, apiKey, secret, passphrase) {
  const timestamp = Date.now().toString();
  const bodyStr = body ?? "";
  const signString = timestamp + method + path + bodyStr;
  const signature = buildBuilderSignature(signString, secret);
  return {
    POLY_BUILDER_SIGNATURE: signature,
    POLY_BUILDER_TIMESTAMP: timestamp,
    POLY_BUILDER_API_KEY: apiKey,
    POLY_BUILDER_PASSPHRASE: passphrase,
  };
}

async function relayerGetNonce(fromAddress) {
  const pathForSign = "/nonce";
  const url = RELAYER_URL + "/nonce?address=" + encodeURIComponent(fromAddress) + "&type=SAFE";
  const headers = getBuilderHeaders(
    "GET",
    pathForSign,
    "",
    process.env.BUILDERS_API_KEY.trim(),
    process.env.BUILDERS_SECRET.trim(),
    process.env.BUILDERS_PASSPHRASE.trim()
  );
  const res = await fetch(url, { method: "GET", headers });
  if (!res.ok) {
    const text = await res.text();
    throw new Error("Relayer getNonce 失败: " + res.status + " " + text);
  }
  const data = await res.json();
  return data.nonce;
}

async function relayerSubmit(requestBody) {
  const pathForSign = "/submit";
  const body = JSON.stringify(requestBody);
  const headers = {
    "Content-Type": "application/json",
    ...getBuilderHeaders(
      "POST",
      pathForSign,
      body,
      process.env.BUILDERS_API_KEY.trim(),
      process.env.BUILDERS_SECRET.trim(),
      process.env.BUILDERS_PASSPHRASE.trim()
    ),
  };
  const res = await fetch(RELAYER_URL + pathForSign, { method: "POST", headers, body });
  const rawText = await res.text();
  if (!res.ok) throw new Error("Relayer submit 失败: " + res.status + " " + rawText);
  const data = JSON.parse(rawText);
  console.log("Relayer submit 原始响应:", JSON.stringify(data, null, 2));
  return data;
}

async function relayerGetTransaction(transactionId) {
  const pathForSign = "/transaction";
  const url = RELAYER_URL + "/transaction?id=" + encodeURIComponent(transactionId);
  const headers = getBuilderHeaders(
    "GET",
    pathForSign,
    "",
    process.env.BUILDERS_API_KEY.trim(),
    process.env.BUILDERS_SECRET.trim(),
    process.env.BUILDERS_PASSPHRASE.trim()
  );
  const res = await fetch(url, { method: "GET", headers });
  const rawText = await res.text();
  if (!res.ok) throw new Error("Relayer getTransaction 失败: " + res.status + " " + rawText);
  const data = JSON.parse(rawText);
  return { parsed: Array.isArray(data) ? data[0] : data, raw: data };
}

function isSuccessState(state) {
  if (!state) return false;
  const s = String(state).toUpperCase();
  return s === "STATE_CONFIRMED" || s === "STATE_MINED" || s === "STATE_EXECUTED";
}

function isFailedState(state) {
  if (!state) return false;
  const s = String(state).toUpperCase();
  return s === "STATE_FAILED" || s === "STATE_INVALID";
}

// 链上交易 hash 应为 0x + 64 个十六进制字符
function isChainTxHash(s) {
  if (!s || typeof s !== "string") return false;
  const t = s.trim();
  return /^0x[0-9a-fA-F]{64}$/.test(t);
}

function getChainTxHashFromTx(tx) {
  if (!tx) return null;
  const candidates = [
    tx.transactionHash,
    tx.hash,
    tx.txHash,
    tx.blockchainHash,
    tx.chainTxHash,
    tx.executionHash,
  ];
  for (const c of candidates) {
    if (isChainTxHash(c)) return c;
  }
  return null;
}

async function pollUntilMined(transactionId, maxAttempts = 30, intervalMs = 3000) {
  for (let i = 0; i < maxAttempts; i++) {
    const { parsed: tx, raw: rawData } = await relayerGetTransaction(transactionId);
    const state = tx?.state;
    if (isSuccessState(state)) {
      console.log("Relayer getTransaction 原始响应 (最终状态):", JSON.stringify(rawData, null, 2));
      const chainHash = getChainTxHashFromTx(tx);
      if (chainHash) return chainHash;
      return null;
    }
    if (isFailedState(state)) {
      console.log("Relayer getTransaction 原始响应 (失败状态):", JSON.stringify(rawData, null, 2));
      const detail = tx.transactionHash ? ` txHash=${tx.transactionHash}` : "";
      const errMsg = tx.errorMessage ?? tx.error ?? tx.revertReason ?? "";
      const full = errMsg ? ` ${errMsg}` : detail;
      throw new Error("Relayer 交易失败: state=" + state + full);
    }
    if (i === 0) console.log("等待 Relayer 确认交易，每", intervalMs / 1000, "秒查询一次...");
    console.log(`  [${i + 1}/${maxAttempts}] state=${state ?? "未知"}`);
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error("等待交易确认超时");
}

async function main() {
  const privateKey = getPrivateKey();
  const safeAddress = process.env.SAFE_ADDRESS?.trim();

  const provider = new ethers.providers.JsonRpcProvider(RPC_URL);
  const wallet = new ethers.Wallet(privateKey, provider);
  const wcol = new ethers.Contract(WCOL_ADDRESS, WCOL_ABI, provider);

  const ownerAddress = wallet.address;
  const targetBalanceAddress = safeAddress || ownerAddress;
  const balanceRaw = await wcol.balanceOf(targetBalanceAddress);
  const decimals = await wcol.decimals();
  const balanceFormatted = ethers.utils.formatUnits(balanceRaw, decimals);

  if (balanceRaw.isZero()) {
    console.log(`地址 ${targetBalanceAddress} 的 WCOL 余额为 0，无需解包。`);
    return;
  }

  console.log(`WCOL 余额: ${balanceFormatted} (${targetBalanceAddress})`);

  if (safeAddress) {
    const iface = new ethers.utils.Interface(WCOL_ABI);
    // 从 Safe 解包时，USDC 默认转到 Safe；设环境变量 UNWRAP_TO_MAIN_WALLET=1 则转到主钱包
    const unwrapTo = process.env.UNWRAP_TO_MAIN_WALLET === "1" ? ownerAddress : safeAddress;
    const unwrapData = iface.encodeFunctionData("unwrap", [unwrapTo, balanceRaw]);

    const safeContract = new ethers.Contract(safeAddress, SAFE_ABI, provider);

    const useRelayer = isBuildersConfigured();
    let nonce;
    if (useRelayer) {
      const relayerNonce = await relayerGetNonce(safeAddress);
      nonce = await safeContract.nonce();
      console.log("Nonce: Relayer 返回=" + relayerNonce.toString() + ", Safe 链上=" + nonce.toString() + "（Gasless 一律用链上 nonce 签名）");
    } else {
      nonce = await safeContract.nonce();
    }

    const domainSeparator = encodeSafeDomain(CHAIN_ID, safeAddress);
    const zero = "0";
    const gasToken = ethers.constants.AddressZero;
    const messageHash = encodeSafeTxMessage(
      WCOL_ADDRESS,
      zero,
      unwrapData,
      0,
      zero,
      zero,
      zero,
      gasToken,
      gasToken,
      nonce
    );
    const structHash = hashStructuredData(domainSeparator, messageHash);
    const signatureHex = await signSafeTx(wallet, structHash);

    if (useRelayer) {
      console.log("使用 Builder Relayer（Gasless），主钱包无需 POL");
      console.log("若链上仍报 GS026，多为 Relayer 上链前 Safe 又执行了其他交易导致 nonce 变化，可改用自付 gas（不设 BUILDERS_*）或确保此时无其他 Safe 交易。");
      const relayerSignature = packSafeSignatureForRelayer(signatureHex);
      const requestBody = {
        type: "SAFE",
        from: ownerAddress,
        to: WCOL_ADDRESS,
        proxyWallet: safeAddress,
        data: unwrapData,
        nonce: nonce.toString(),
        signature: relayerSignature,
        signatureParams: {
          gasPrice: "0",
          operation: "0",
          safeTxnGas: "0",
          baseGas: "0",
          gasToken: ethers.constants.AddressZero,
          refundReceiver: ethers.constants.AddressZero,
        },
        metadata: "unwrap WCOL to USDC.e",
      };
      const result = await relayerSubmit(requestBody);
      const txId = result.transactionID ?? result.transactionId ?? result.id ?? result.transaction_id;
      if (!txId) {
        console.log("Relayer 已提交，响应:", JSON.stringify(result, null, 2));
        return;
      }
      console.log("Relayer 已提交，transactionId:", txId);
      const txHash = await pollUntilMined(txId);
      if (txHash) {
        console.log("交易已确认 (Polygon):", txHash);
      } else {
        console.log("Relayer 已标记为成功，但未返回链上 tx hash。可在 https://polygonscan.com 用 Safe 地址查看最新交易:", safeAddress);
        console.log("若需排查，可设置 DEBUG=1 查看 Relayer 返回的完整数据");
      }
      console.log("已解包", balanceFormatted, "WCOL → USDC.e，到账地址:", unwrapTo);
      return;
    }

    const execCalldata = buildExecTransactionCalldata(WCOL_ADDRESS, unwrapData, 0, signatureHex);
    console.log("正在由 Safe 执行 unwrap，主钱包发送交易（需少量 POL 付 gas）...");
    const tx = await wallet.sendTransaction({
      to: safeAddress,
      data: execCalldata,
      value: 0,
      gasLimit: 500000,
    });
    console.log("Tx hash:", tx.hash);
    const receipt = await tx.wait();
    console.log("已确认，block:", receipt.blockNumber);
    console.log("已解包", balanceFormatted, "WCOL → USDC.e，到账地址:", unwrapTo);
    return;
  }

  console.log("正在发送 unwrap 交易，USDC.e 将转到:", ownerAddress);
  const tx = await wcol.connect(wallet).unwrap(ownerAddress, balanceRaw);
  console.log("Tx hash:", tx.hash);
  const receipt = await tx.wait();
  console.log("已确认，block:", receipt.blockNumber);
  console.log("已解包", balanceFormatted, "WCOL → USDC.e");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
