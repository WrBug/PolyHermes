import { useState, useEffect } from 'react'
import { Card, Table, Button, Space, Badge, message, Popconfirm, Tag, Switch } from 'antd'
import { UpOutlined, DownOutlined, DeleteOutlined, ReloadOutlined, PlusOutlined, ApiOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { RpcNodeConfig } from '../types'
import AddRpcNodeModal from '../components/AddRpcNodeModal'
import { useTranslation } from 'react-i18next'

const RpcNodeSettings: React.FC = () => {
    const { t } = useTranslation()
    const [nodes, setNodes] = useState<RpcNodeConfig[]>([])
    const [loading, setLoading] = useState(false)
    const [checking, setChecking] = useState(false)
    const [modalVisible, setModalVisible] = useState(false)

    useEffect(() => {
        fetchNodes()
    }, [])

    const fetchNodes = async () => {
        setLoading(true)
        try {
            const response = await apiService.rpcNodes.list()
            if (response.data.code === 0 && response.data.data) {
                setNodes(response.data.data)
            } else {
                message.error(response.data.msg || t('rpcNodeSettings.fetchFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('rpcNodeSettings.fetchFailed'))
        } finally {
            setLoading(false)
        }
    }

    const handleCheckAllHealth = async () => {
        setChecking(true)
        try {
            const response = await apiService.rpcNodes.checkHealth({})
            if (response.data.code === 0) {
                message.success(t('rpcNodeSettings.checkHealthSuccess'))
                fetchNodes()
            } else {
                message.error(response.data.msg || t('rpcNodeSettings.checkHealthFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('rpcNodeSettings.checkHealthFailed'))
        } finally {
            setChecking(false)
        }
    }

    const handleDelete = async (id: number) => {
        try {
            const response = await apiService.rpcNodes.delete({ id })
            if (response.data.code === 0) {
                message.success(t('rpcNodeSettings.deleteSuccess'))
                fetchNodes()
            } else {
                message.error(response.data.msg || t('rpcNodeSettings.deleteFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('rpcNodeSettings.deleteFailed'))
        }
    }

    const handleMovePriority = async (id: number, direction: 'up' | 'down') => {
        const index = nodes.findIndex(n => n.id === id)
        if (index === -1) return

        if (direction === 'up' && index === 0) return
        if (direction === 'down' && index === nodes.length - 1) return

        const targetIndex = direction === 'up' ? index - 1 : index + 1
        const newPriority = nodes[targetIndex].priority

        try {
            const response = await apiService.rpcNodes.updatePriority({ id, priority: newPriority })
            if (response.data.code === 0) {
                // 同时更新另一个节点的优先级
                await apiService.rpcNodes.updatePriority({
                    id: nodes[targetIndex].id,
                    priority: nodes[index].priority
                })
                message.success(t('rpcNodeSettings.adjustPrioritySuccess'))
                fetchNodes()
            } else {
                message.error(response.data.msg || t('rpcNodeSettings.adjustPriorityFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('rpcNodeSettings.adjustPriorityFailed'))
        }
    }

    const handleToggleEnabled = async (id: number, enabled: boolean) => {
        try {
            const response = await apiService.rpcNodes.update({ id, enabled })
            if (response.data.code === 0) {
                message.success(enabled ? t('rpcNodeSettings.enableSuccess') : t('rpcNodeSettings.disableSuccess'))
                fetchNodes()
            } else {
                message.error(response.data.msg || t('rpcNodeSettings.updateFailed'))
            }
        } catch (error: any) {
            message.error(error.message || t('rpcNodeSettings.updateFailed'))
        }
    }

    const columns = [
        {
            title: t('rpcNodeSettings.priority'),
            dataIndex: 'priority',
            width: 120,
            render: (_: any, record: RpcNodeConfig, index: number) => (
                <Space>
                    <Button
                        size="small"
                        icon={<UpOutlined />}
                        onClick={() => handleMovePriority(record.id, 'up')}
                        disabled={index === 0}
                    />
                    <Button
                        size="small"
                        icon={<DownOutlined />}
                        onClick={() => handleMovePriority(record.id, 'down')}
                        disabled={index === nodes.length - 1}
                    />
                    <span>{index + 1}</span>
                </Space>
            )
        },
        {
            title: t('rpcNodeSettings.providerType'),
            dataIndex: 'providerType',
            width: 120,
            render: (type: string) => <Tag color="blue">{type}</Tag>
        },
        {
            title: t('rpcNodeSettings.name'),
            dataIndex: 'name',
            ellipsis: true
        },
        {
            title: t('rpcNodeSettings.enabled'),
            dataIndex: 'enabled',
            width: 100,
            render: (enabled: boolean, record: RpcNodeConfig) => (
                <Switch
                    checked={enabled}
                    onChange={(checked) => handleToggleEnabled(record.id, checked)}
                />
            )
        },
        {
            title: t('rpcNodeSettings.status'),
            dataIndex: 'lastCheckStatus',
            width: 100,
            render: (status: string | undefined) => {
                const statusMap = {
                    HEALTHY: { status: 'success' as const, text: t('rpcNodeSettings.statusHealthy') },
                    UNHEALTHY: { status: 'error' as const, text: t('rpcNodeSettings.statusUnhealthy') },
                    UNKNOWN: { status: 'default' as const, text: t('rpcNodeSettings.statusUnknown') }
                }
                const config = statusMap[status as keyof typeof statusMap] || statusMap.UNKNOWN
                return <Badge status={config.status} text={config.text} />
            }
        },
        {
            title: t('rpcNodeSettings.responseTime'),
            dataIndex: 'responseTimeMs',
            width: 100,
            render: (time: number | undefined) => time ? `${time}ms` : '-'
        },
        {
            title: t('rpcNodeSettings.actions'),
            key: 'action',
            width: 150,
            render: (_: any, record: RpcNodeConfig) => (
                <Space size="small">
                    <Button
                        size="small"
                        onClick={async () => {
                            try {
                                const response = await apiService.rpcNodes.checkHealth({ id: record.id })
                                if (response.data.code === 0) {
                                    message.success(t('rpcNodeSettings.checkSuccess'))
                                    fetchNodes()
                                }
                            } catch (error: any) {
                                message.error(t('rpcNodeSettings.checkFailed'))
                            }
                        }}
                    >
                        {t('rpcNodeSettings.check')}
                    </Button>
                    <Popconfirm
                        title={t('rpcNodeSettings.deleteConfirm')}
                        onConfirm={() => handleDelete(record.id)}
                        okText={t('rpcNodeSettings.deleteConfirmOk')}
                        cancelText={t('rpcNodeSettings.deleteConfirmCancel')}
                    >
                        <Button size="small" danger icon={<DeleteOutlined />}>
                            {t('rpcNodeSettings.delete')}
                        </Button>
                    </Popconfirm>
                </Space>
            )
        }
    ]

    return (
        <Card
            title={
                <Space>
                    <ApiOutlined />
                    <span>{t('rpcNodeSettings.title')}</span>
                </Space>
            }
            extra={
                <Space>
                    <Button
                        icon={<ReloadOutlined />}
                        onClick={handleCheckAllHealth}
                        loading={checking}
                    >
                        {t('rpcNodeSettings.batchCheck')}
                    </Button>
                    <Button
                        type="primary"
                        icon={<PlusOutlined />}
                        onClick={() => setModalVisible(true)}
                    >
                        {t('rpcNodeSettings.addNode')}
                    </Button>
                </Space>
            }
        >
            <Table
                dataSource={nodes}
                columns={columns}
                rowKey="id"
                loading={loading}
                pagination={false}
            />

            <AddRpcNodeModal
                visible={modalVisible}
                onCancel={() => setModalVisible(false)}
                onSuccess={() => {
                    setModalVisible(false)
                    fetchNodes()
                }}
            />
        </Card>
    )
}

export default RpcNodeSettings
