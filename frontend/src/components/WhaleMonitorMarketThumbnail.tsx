interface WhaleMonitorMarketThumbnailProps {
  src?: string
  size?: number
  alt?: string
}

const WhaleMonitorMarketThumbnail: React.FC<WhaleMonitorMarketThumbnailProps> = ({
  src,
  size = 40,
  alt = ''
}) => {
  if (!src?.trim()) return null

  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      style={{
        width: size,
        height: size,
        borderRadius: 6,
        objectFit: 'cover',
        flexShrink: 0,
        background: '#f5f5f5'
      }}
      onError={e => {
        const target = e.currentTarget
        target.style.display = 'none'
      }}
    />
  )
}

export default WhaleMonitorMarketThumbnail
