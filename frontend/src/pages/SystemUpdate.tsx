import { useState, useEffect } from 'react'
import { Card, Button, Spin, Progress, Alert, Space, Typography, Divider, Tag, Modal, message } from 'antd'
import {
    CloudUploadOutlined,
    ReloadOutlined,
    CheckCircleOutlined,
    ExclamationCircleOutlined,
    InfoCircleOutlined
} from '@ant-design/icons'
import { apiClient } from '../services/api'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const { Title, Text, Paragraph } = Typography

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
    const [currentVersion, setCurrentVersion] = useState('')
    const [updateChecking, setUpdateChecking] = useState(false)
    const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
    const [updateStatus, setUpdateStatus] = useState<UpdateStatus>({
        updating: false,
        progress: 0,
        message: '就绪',
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
                    message: response.data.data.message || '就绪',
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
                    message.success(`发现新版本: ${data.data.latestVersion}`)
                } else {
                    message.info('当前已是最新版本')
                }
            } else {
                message.error(data.message || '检查更新失败')
            }
        } catch (error: any) {
            message.error(error.message || '检查更新失败')
        } finally {
            setUpdateChecking(false)
        }
    }

    const handleExecuteUpdate = () => {
        Modal.confirm({
            title: '确认更新',
            icon: <ExclamationCircleOutlined />,
            content: (
                <div>
                    <p>确定要更新到版本 <strong>{updateInfo?.latestVersion}</strong> 吗？</p>
                    <p>更新过程中系统将暂时不可用（约30-60秒）。</p>
                    <p>更新完成后页面将自动刷新。</p>
                </div>
            ),
            okText: '立即更新',
            okType: 'primary',
            cancelText: '取消',
            onOk: async () => {
                try {
                    const response = await apiClient.post('/update/execute', {})
                    const data = response.data

                    if (data.code === 0) {
                        message.success('更新已启动，请稍候...')

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
                                            message.error(`更新失败: ${statusData.data.error}`)
                                        } else if (statusData.data.progress === 100) {
                                            message.success('更新成功！页面将在3秒后刷新...')
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
                        message.error('需要管理员权限才能执行更新')
                    } else {
                        message.error(data.message || '启动更新失败')
                    }
                } catch (error: any) {
                    message.error(error.message || '启动更新失败')
                }
            }
        })
    }

    const formatDate = (dateString: string) => {
        return new Date(dateString).toLocaleString('zh-CN')
    }

    return (
        <Card
            title={
                <Space>
                    <CloudUploadOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                    <span style={{ fontSize: '16px', fontWeight: 600 }}>系统更新</span>
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
                            当前版本
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
                            <span style={{ fontSize: '15px', fontWeight: 500 }}>系统正在更新</span>
                        }
                        description={
                            <div style={{ marginTop: '12px' }}>
                                <div style={{ 
                                    marginBottom: '12px', 
                                    fontSize: '14px', 
                                    color: '#595959' 
                                }}>
                                    {updateStatus.message}
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
                        message={<span style={{ fontSize: '15px', fontWeight: 500 }}>更新失败</span>}
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
                            检查更新
                        </Button>

                        {updateInfo && !updateInfo.hasUpdate && (
                            <Alert
                                message={
                                    <span style={{ fontSize: '15px', fontWeight: 500 }}>
                                        当前已是最新版本
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
                                    发现新版本
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
                                            Pre-release
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
                                发布时间
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
                                    更新内容
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
                            立即升级到 v{updateInfo.latestVersion}
                        </Button>
                    </div>
                )}

                {/* 使用提示 */}
                {!updateStatus.updating && !(updateInfo && updateInfo.hasUpdate) && (
                    <Alert
                        message={
                            <span style={{ fontSize: '15px', fontWeight: 500 }}>使用说明</span>
                        }
                        description={
                            <ul style={{ 
                                marginBottom: 0, 
                                paddingLeft: '20px',
                                fontSize: '14px',
                                color: '#595959',
                                lineHeight: '1.8'
                            }}>
                                <li>点击"检查更新"按钮检查是否有新版本</li>
                                <li>更新过程约需30-60秒，期间系统将暂时不可用</li>
                                <li>更新成功后页面将自动刷新</li>
                                <li>如果更新失败，系统会自动回滚到当前版本</li>
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
