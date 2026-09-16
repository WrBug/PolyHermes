/**
 * 钱包类型展示辅助：后端 walletType 为 "magic" | "safe" | "deposit"
 */
export type WalletTypeValue = 'magic' | 'safe' | 'deposit'

export const normalizeWalletType = (walletType?: string | null): WalletTypeValue | null => {
  if (!walletType) return null
  const type = walletType.toLowerCase()
  if (type === 'magic' || type === 'safe' || type === 'deposit') return type
  return null
}

/** 标签文案（品牌名，不做翻译） */
export const walletTypeLabel = (walletType?: string | null): string => {
  switch (normalizeWalletType(walletType)) {
    case 'magic':
      return 'Magic'
    case 'deposit':
      return 'Deposit Wallet'
    case 'safe':
      return 'Safe'
    default:
      return walletType || '-'
  }
}

/** antd Tag 颜色 */
export const walletTypeColor = (walletType?: string | null): string => {
  switch (normalizeWalletType(walletType)) {
    case 'magic':
      return 'purple'
    case 'deposit':
      return 'green'
    default:
      return 'blue'
  }
}
