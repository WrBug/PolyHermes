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
                    <CloudUploadOutlined />
                    <span>系统更新</span>
                </Space>
            }
            style={{ marginBottom: '16px' }}
        >
            <Space direction="vertical" style={{ width: '100%' }} size="large">
                {/* 当前版本信息 */}
                <div>
                    <Title level={5}>当前版本</Title>
                    <Space>
                        <Tag color="blue" style={{ fontSize: '14px', padding: '4px 12px' }}>
                            v{currentVersion || 'unknown'}
                        </Tag>
                    </Space>
                </div>

                <Divider />

                {/* 更新状态 */}
                {updateStatus.updating && (
                    <Alert
                        message="系统正在更新"
                        description={
                            <div>
                                <p>{updateStatus.message}</p>
                                <Progress
                                    percent={updateStatus.progress}
                                    status="active"
                                    strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
                                />
                            </div>
                        }
                        type="info"
                        showIcon
                        icon={<Spin />}
                    />
                )}

                {updateStatus.error && (
                    <Alert
                        message="更新失败"
                        description={updateStatus.error}
                        type="error"
                        showIcon
                        closable
                        onClose={() => setUpdateStatus(prev => ({ ...prev, error: null }))}
                    />
                )}

                {/* 检查更新 */}
                {!updateStatus.updating && (
                    <Space>
                        <Button
                            type="primary"
                            icon={<ReloadOutlined />}
                            onClick={handleCheckUpdate}
                            loading={updateChecking}
                        >
                            检查更新
                        </Button>

                        {updateInfo && !updateInfo.hasUpdate && (
                            <Alert
                                message="当前已是最新版本"
                                type="success"
                                showIcon
                                icon={<CheckCircleOutlined />}
                            />
                        )}
                    </Space>
                )}

                {/* 更新信息 */}
                {updateInfo && updateInfo.hasUpdate && !updateStatus.updating && (
                    <Alert
                        message={
                            <Space>
                                <span>发现新版本:</span>
                                <Tag color="green" style={{ fontSize: '14px' }}>
                                    v{updateInfo.latestVersion}
                                </Tag>
                                {updateInfo.prerelease && (
                                    <Tag color="orange">Pre-release</Tag>
                                )}
                            </Space>
                        }
                        description={
                            <div style={{ marginTop: '12px' }}>
                                <Paragraph>
                                    <Text strong>发布时间：</Text>
                                    <Text type="secondary">{formatDate(updateInfo.publishedAt)}</Text>
                                </Paragraph>

                                {updateInfo.releaseNotes && (
                                    <div>
                                        <Text strong>更新内容：</Text>
                                        <div style={{
                                            marginTop: '8px',
                                            padding: '12px',
                                            background: '#f5f5f5',
                                            borderRadius: '4px',
                                            maxHeight: '200px',
                                            overflowY: 'auto'
                                        }}>
                                            <pre style={{
                                                margin: 0,
                                                whiteSpace: 'pre-wrap',
                                                fontFamily: 'inherit'
                                            }}>
                                                {updateInfo.releaseNotes}
                                            </pre>
                                        </div>
                                    </div>
                                )}

                                <div style={{ marginTop: '16px' }}>
                                    <Button
                                        type="primary"
                                        icon={<CloudUploadOutlined />}
                                        onClick={handleExecuteUpdate}
                                    >
                                        立即升级
                                    </Button>
                                </div>
                            </div>
                        }
                        type="warning"
                        showIcon
                        icon={<InfoCircleOutlined />}
                    />
                )}

                {/* 使用提示 */}
                {!updateStatus.updating && (
                    <Alert
                        message="使用说明"
                        description={
                            <ul style={{ marginBottom: 0, paddingLeft: '20px' }}>
                                <li>点击"检查更新"按钮检查是否有新版本</li>
                                <li>更新过程约需30-60秒，期间系统将暂时不可用</li>
                                <li>更新成功后页面将自动刷新</li>
                                <li>如果更新失败，系统会自动回滚到当前版本</li>
                            </ul>
                        }
                        type="info"
                        showIcon
                    />
                )}
            </Space>
        </Card>
    )
}

export default SystemUpdate
