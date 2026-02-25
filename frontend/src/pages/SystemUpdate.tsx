import { useState, useEffect } from 'react'
import { Card, Button, Spin, Progress, Alert, Space, Tag, Modal, message } from 'antd'
import {
    CloudUploadOutlined,
    ReloadOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined
} from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiClient } from '../services/api'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'


interface UpdateInfo {
    hasUpdate: boolean
    currentVersion: string
    latestVersion: string
    latestTag: string
    releaseNotes: string
    publishedAt: string
    prerelease: boolean
}

interface UpdateStatus {
    updating: boolean
    progress: number
    message: string
    error: string | null
}

const SystemUpdate: React.FC = () => {
    const { t, i18n } = useTranslation()
    const [currentVersion, setCurrentVersion] = useState('')
    const [updateChecking, setUpdateChecking] = useState(false)
    const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
    const [updateStatus, setUpdateStatus] = useState<UpdateStatus>({
        updating: false,
        progress: 0,
        message: '',
        error: null
    })

    useEffect(() => {
        fetchCurrentVersion()
        fetchUpdateStatus()
    }, [])

    const fetchCurrentVersion = async () => {
        try {
            const response = await apiClient.get('/update/version')
            if (response.data.code === 0 && response.data.data) {
                setCurrentVersion(response.data.data.version)
            }
        } catch (error: any) {
            console.error('获取版本失败:', error)
        }
    }

    const fetchUpdateStatus = async () => {
        try {
            const response = await apiClient.get('/update/status')
            if (response.data.code === 0 && response.data.data) {
                setUpdateStatus({
                    updating: response.data.data.updating,
                    progress: response.data.data.progress || 0,
                    message: response.data.data.message || '',
                    error: response.data.data.error || null
                })
            }
        } catch (error: any) {
            console.error('获取更新状态失败:', error)
        }
    }

    const handleCheckUpdate = async () => {
        setUpdateChecking(true)
        setUpdateInfo(null)

        try {
            const response = await apiClient.get('/update/check')
            const data = response.data

            if (data.code === 0 && data.data) {
                setUpdateInfo(data.data)

                if (data.data.hasUpdate) {
                    message.success(t('systemUpdate.hasNewVersion', { version: data.data.latestVersion }))
                } else {
                    message.info(t('systemUpdate.alreadyLatest'))
                }
            } else {
                message.error(data.message || t('systemUpdate.checkFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('systemUpdate.checkFailed'))
        } finally {
            setUpdateChecking(false)
        }
    }

    const handleExecuteUpdate = () => {
        Modal.confirm({
            title: t('systemUpdate.confirmTitle'),
            icon: <ExclamationCircleOutlined />,
            content: (
                <div>
                    <p>{t('systemUpdate.confirmContent1', { version: updateInfo?.latestVersion })}</p>
                    <p>{t('systemUpdate.confirmContent2')}</p>
                    <p>{t('systemUpdate.confirmContent3')}</p>
                </div>
            ),
            okText: t('systemUpdate.okText'),
            okType: 'primary',
            cancelText: t('systemUpdate.cancelText'),
            onOk: async () => {
                try {
                    const response = await apiClient.post('/update/update', {})
                    const data = response.data

                    if (data.code === 0) {
                        message.success(t('systemUpdate.updateStarted'))

                        // 开始轮询更新状态
                        const pollInterval = setInterval(async () => {
                            try {
                                const statusResponse = await apiClient.get('/update/status')
                                const statusData = statusResponse.data

                                if (statusData.code === 0 && statusData.data) {
                                    setUpdateStatus({
                                        updating: statusData.data.updating,
                                        progress: statusData.data.progress || 0,
                                        message: statusData.data.message || '',
                                        error: statusData.data.error || null
                                    })

                                    // 更新完成
                                    if (!statusData.data.updating) {
                                        clearInterval(pollInterval)

                                        if (statusData.data.error) {
                                            message.error(t('systemUpdate.updateFailedWithMessage', { message: statusData.data.error }))
                                        } else if (statusData.data.progress === 100) {
                                            message.success(t('systemUpdate.updateSuccessRefresh'))
                                            setTimeout(() => window.location.reload(), 3000)
                                        }
                                    }
                                }
                            } catch (error) {
                                console.error('获取更新状态失败:', error)
                            }
                        }, 2000) // 每2秒轮询一次

                        // 5分钟后停止轮询
                        setTimeout(() => clearInterval(pollInterval), 5 * 60 * 1000)
                    } else if (data.code === 403) {
                        message.error(t('systemUpdate.needAdmin'))
                    } else {
                        message.error(data.message || t('systemUpdate.startFailed'))
                    }
                } catch (error: any) {
                    message.error(error.message || t('systemUpdate.startFailed'))
                }
            }
        })
    }

    const formatDate = (dateString: string) => {
        return new Date(dateString).toLocaleString(i18n.language === 'zh-CN' ? 'zh-CN' : i18n.language === 'zh-TW' ? 'zh-TW' : 'en')
    }

    return (
        <Card
            title={
                <Space>
                    <CloudUploadOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                    <span style={{ fontSize: '16px', fontWeight: 600 }}>{t('systemUpdate.title')}</span>
                </Space>
            }
            style={{ 
                marginBottom: '16px',
                borderRadius: '8px',
                boxShadow: '0 2px 8px rgba(0,0,0,0.06)'
            }}
        >
            <Space direction="vertical" style={{ width: '100%' }} size="large">
                {/* 当前版本信息 */}
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '16px',
                    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                    borderRadius: '8px',
                    color: '#fff'
                }}>
                    <div>
                        <div style={{ fontSize: '13px', opacity: 0.9, marginBottom: '4px' }}>
                            {t('systemUpdate.currentVersion')}
                        </div>
                        <div style={{ fontSize: '20px', fontWeight: 600 }}>
                            v{currentVersion || 'unknown'}
                        </div>
                    </div>
                    <CheckCircleOutlined style={{ fontSize: '32px', opacity: 0.8 }} />
                </div>

                {/* 更新状态 */}
                {updateStatus.updating && (
                    <Alert
                        message={
                            <span style={{ fontSize: '15px', fontWeight: 500 }}>{t('systemUpdate.updating')}</span>
                        }
                        description={
                            <div style={{ marginTop: '12px' }}>
                                <div style={{ 
                                    marginBottom: '12px', 
                                    fontSize: '14px', 
                                    color: '#595959'
                                }}>
                                    {updateStatus.message || t('systemUpdate.ready')}
                                </div>
                                <Progress
                                    percent={updateStatus.progress}
                                    status="active"
                                    strokeColor={{ 
                                        '0%': '#667eea', 
                                        '50%': '#764ba2',
                                        '100%': '#f093fb' 
                                    }}
                                    strokeWidth={8}
                                    showInfo
                                    format={(percent) => `${percent}%`}
                                />
                            </div>
                        }
                        type="info"
                        showIcon
                        icon={<Spin />}
                        style={{
                            borderRadius: '8px',
                            border: '1px solid #91d5ff'
                        }}
                    />
                )}

                {updateStatus.error && (
                    <Alert
                        message={<span style={{ fontSize: '15px', fontWeight: 500 }}>{t('systemUpdate.updateFailedTitle')}</span>}
                        description={
                            <div style={{ 
                                marginTop: '8px', 
                                fontSize: '14px',
                                color: '#595959'
                            }}>
                                {updateStatus.error}
                            </div>
                        }
                        type="error"
                        showIcon
                        closable
                        onClose={() => setUpdateStatus(prev => ({ ...prev, error: null }))}
                        style={{
                            borderRadius: '8px'
                        }}
                    />
                )}

                {/* 检查更新 */}
                {!updateStatus.updating && (
                    <div>
                        <Button
                            type="primary"
                            size="large"
                            icon={<ReloadOutlined />}
                            onClick={handleCheckUpdate}
                            loading={updateChecking}
                            style={{
                                height: '40px',
                                borderRadius: '6px',
                                fontWeight: 500,
                                boxShadow: '0 2px 4px rgba(24, 144, 255, 0.2)'
                            }}
                        >
                            {t('systemUpdate.checkUpdate')}
                        </Button>

                        {updateInfo && !updateInfo.hasUpdate && (
                            <Alert
                                message={
                                    <span style={{ fontSize: '15px', fontWeight: 500 }}>
                                        {t('systemUpdate.alreadyLatest')}
                                    </span>
                                }
                                type="success"
                                showIcon
                                icon={<CheckCircleOutlined />}
                                style={{
                                    marginTop: '12px',
                                    borderRadius: '8px',
                                    border: '1px solid #b7eb8f'
                                }}
                            />
                        )}
                    </div>
                )}

                {/* 更新信息 */}
                {updateInfo && updateInfo.hasUpdate && !updateStatus.updating && (
                    <div style={{
                        padding: '20px',
                        background: 'linear-gradient(135deg, #fff5f5 0%, #fff1f0 100%)',
                        borderRadius: '8px',
                        border: '1px solid #ffccc7'
                    }}>
                        <div style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            marginBottom: '16px',
                            paddingBottom: '16px',
                            borderBottom: '1px solid #ffccc7'
                        }}>
                            <div>
                                <div style={{
                                    fontSize: '13px',
                                    color: '#8c8c8c',
                                    marginBottom: '6px'
                                }}>
                                    {t('systemUpdate.newVersionFound')}
                                </div>
                                <Space size="small">
                                    <Tag 
                                        color="success" 
                                        style={{ 
                                            fontSize: '16px', 
                                            padding: '4px 16px',
                                            fontWeight: 600,
                                            borderRadius: '4px'
                                        }}
                                    >
                                        v{updateInfo.latestVersion}
                                    </Tag>
                                    {updateInfo.prerelease && (
                                        <Tag
                                            color="orange"
                                            style={{
                                                fontSize: '12px',
                                                padding: '4px 12px',
                                                borderRadius: '4px'
                                            }}
                                        >
                                            {t('systemUpdate.prerelease')}
                                        </Tag>
                                    )}
                                </Space>
                            </div>
                            <CloudUploadOutlined style={{ 
                                fontSize: '32px', 
                                color: '#ff4d4f',
                                opacity: 0.8
                            }} />
                        </div>

                        <div style={{ marginBottom: '16px' }}>
                            <div style={{
                                fontSize: '13px',
                                color: '#8c8c8c',
                                marginBottom: '4px'
                            }}>
                                {t('systemUpdate.publishedAt')}
                            </div>
                            <div style={{
                                fontSize: '14px',
                                color: '#595959'
                            }}>
                                {formatDate(updateInfo.publishedAt)}
                            </div>
                        </div>

                        {updateInfo.releaseNotes && (
                            <div style={{ marginBottom: '20px' }}>
                                <div style={{
                                    fontSize: '13px',
                                    color: '#8c8c8c',
                                    marginBottom: '8px',
                                    fontWeight: 500
                                }}>
                                    {t('systemUpdate.releaseNotes')}
                                </div>
                                <div style={{
                                    padding: '16px',
                                    background: '#fff',
                                    borderRadius: '6px',
                                    border: '1px solid #e8e8e8',
                                    maxHeight: '500px',
                                    overflowY: 'auto',
                                    lineHeight: '1.6',
                                    boxShadow: 'inset 0 1px 2px rgba(0,0,0,0.06)'
                                }}>
                                    <div style={{
                                        color: '#262626',
                                        fontSize: '14px'
                                    }}>
                                        <ReactMarkdown 
                                            remarkPlugins={[remarkGfm]}
                                            components={{
                                                h1: ({node, ...props}) => <h1 style={{ fontSize: '20px', fontWeight: 600, marginTop: '16px', marginBottom: '12px', color: '#262626', borderBottom: '2px solid #e8e8e8', paddingBottom: '8px' }} {...props} />,
                                                h2: ({node, ...props}) => <h2 style={{ fontSize: '18px', fontWeight: 600, marginTop: '16px', marginBottom: '10px', color: '#262626' }} {...props} />,
                                                h3: ({node, ...props}) => <h3 style={{ fontSize: '16px', fontWeight: 600, marginTop: '14px', marginBottom: '8px', color: '#262626' }} {...props} />,
                                                h4: ({node, ...props}) => <h4 style={{ fontSize: '15px', fontWeight: 600, marginTop: '12px', marginBottom: '6px', color: '#262626' }} {...props} />,
                                                p: ({node, ...props}) => <p style={{ marginBottom: '12px', color: '#595959' }} {...props} />,
                                                ul: ({node, ...props}) => <ul style={{ marginBottom: '12px', paddingLeft: '24px', color: '#595959' }} {...props} />,
                                                ol: ({node, ...props}) => <ol style={{ marginBottom: '12px', paddingLeft: '24px', color: '#595959' }} {...props} />,
                                                li: ({node, ...props}) => <li style={{ marginBottom: '6px', lineHeight: '1.6' }} {...props} />,
                                                code: ({node, inline, ...props}: any) => 
                                                    inline 
                                                        ? <code style={{ background: '#f0f0f0', padding: '2px 6px', borderRadius: '3px', fontSize: '13px', fontFamily: 'Monaco, Menlo, "Ubuntu Mono", Consolas, monospace', color: '#d73a49' }} {...props} />
                                                        : <code style={{ display: 'block', background: '#282c34', color: '#abb2bf', padding: '12px', borderRadius: '4px', overflowX: 'auto', fontSize: '13px', fontFamily: 'Monaco, Menlo, "Ubuntu Mono", Consolas, monospace', marginBottom: '12px', lineHeight: '1.5' }} {...props} />,
                                                pre: ({node, ...props}) => <pre style={{ background: '#282c34', borderRadius: '4px', padding: '12px', overflowX: 'auto', marginBottom: '12px' }} {...props} />,
                                                blockquote: ({node, ...props}) => <blockquote style={{ borderLeft: '4px solid #1890ff', paddingLeft: '12px', margin: '12px 0', color: '#8c8c8c', fontStyle: 'italic' }} {...props} />,
                                                a: ({node, ...props}) => <a style={{ color: '#1890ff', textDecoration: 'none' }} target="_blank" rel="noopener noreferrer" {...props} />,
                                                strong: ({node, ...props}) => <strong style={{ fontWeight: 600, color: '#262626' }} {...props} />,
                                                table: ({node, ...props}) => <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: '12px' }} {...props} />,
                                                th: ({node, ...props}) => <th style={{ border: '1px solid #e8e8e8', padding: '8px 12px', background: '#fafafa', fontWeight: 600, textAlign: 'left' }} {...props} />,
                                                td: ({node, ...props}) => <td style={{ border: '1px solid #e8e8e8', padding: '8px 12px' }} {...props} />,
                                                hr: ({node, ...props}) => <hr style={{ border: 'none', borderTop: '1px solid #e8e8e8', margin: '16px 0' }} {...props} />
                                            }}
                                        >
                                            {updateInfo.releaseNotes}
                                        </ReactMarkdown>
                                    </div>
                                </div>
                            </div>
                        )}

                        <Button
                            type="primary"
                            size="large"
                            icon={<CloudUploadOutlined />}
                            onClick={handleExecuteUpdate}
                            block
                            style={{
                                height: '44px',
                                borderRadius: '6px',
                                fontWeight: 500,
                                fontSize: '15px',
                                background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                                border: 'none',
                                boxShadow: '0 4px 12px rgba(102, 126, 234, 0.4)'
                            }}
                        >
                            {t('systemUpdate.upgradeNow', { version: updateInfo.latestVersion })}
                        </Button>
                    </div>
                )}

                {/* 使用提示 */}
                {!updateStatus.updating && !(updateInfo && updateInfo.hasUpdate) && (
                    <Alert
                        message={
                            <span style={{ fontSize: '15px', fontWeight: 500 }}>{t('systemUpdate.usageTitle')}</span>
                        }
                        description={
                            <ul style={{
                                marginBottom: 0,
                                paddingLeft: '20px',
                                fontSize: '14px',
                                color: '#595959',
                                lineHeight: '1.8'
                            }}>
                                <li>{t('systemUpdate.usage1')}</li>
                                <li>{t('systemUpdate.usage2')}</li>
                                <li>{t('systemUpdate.usage3')}</li>
                                <li>{t('systemUpdate.usage4')}</li>
                            </ul>
                        }
                        type="info"
                        showIcon
                        style={{
                            borderRadius: '8px',
                            border: '1px solid #91d5ff'
                        }}
                    />
                )}
            </Space>
        </Card>
    )
}

export default SystemUpdate
