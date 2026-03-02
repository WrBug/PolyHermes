import React, { useEffect, useState, useCallback } from 'react'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Typography, Modal, Form, Input, Switch, Tooltip, Row, Col, Tabs } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, SendOutlined, CopyOutlined, ReloadOutlined, CheckOutlined, RobotOutlined, FormOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { NotificationConfig, NotificationConfigRequest, NotificationConfigUpdateRequest, NotificationTemplate, TemplateTypeInfo, TemplateVariablesResponse, TemplateVariable } from '../types'
import { useMediaQuery } from 'react-responsive'
import { TelegramConfigForm } from '../components/notifications'
import TextArea from 'antd/es/input/TextArea'

const { Title, Text, Paragraph } = Typography

const variableTagStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  cursor: 'pointer',
  marginBottom: 6,
  marginRight: 6,
  borderRadius: 6,
  padding: '4px 10px',
  fontSize: 12,
  transition: 'all 0.2s ease',
  border: '1px solid #e8e8e8',
  background: '#ffffff',
  color: 'rgba(0, 0, 0, 0.65)',
}

const variableTagHoverStyle: React.CSSProperties = {
  borderColor: '#1890ff',
  background: '#e6f7ff',
  color: '#1890ff',
  transform: 'translateY(-1px)',
  boxShadow: '0 2px 4px rgba(24, 144, 255, 0.2)',
}

/**
 * 变量分类标签映射
 */
const CATEGORY_LABELS: Record<string, string> = {
  common: 'notificationSettings.templates.commonVariables',
  order: 'notificationSettings.templates.orderVariables',
  copy_trading: 'notificationSettings.templates.copyTradingVariables',
  redeem: 'notificationSettings.templates.redeemVariables',
  error: 'notificationSettings.templates.errorVariables',
  filter: 'notificationSettings.templates.filterVariables',
  strategy: 'notificationSettings.templates.strategyVariables'
}

const NotificationSettingsPage: React.FC = () => {
  const { t } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })

  // 机器人配置相关状态
  const [configs, setConfigs] = useState<NotificationConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [modalVisible, setModalVisible] = useState(false)
  const [editingConfig, setEditingConfig] = useState<NotificationConfig | null>(null)
  const [form] = Form.useForm()
  const [testLoading, setTestLoading] = useState(false)

  // 模板配置相关状态
  const [templateTypes, setTemplateTypes] = useState<TemplateTypeInfo[]>([])
  const [selectedTemplateType, setSelectedTemplateType] = useState<string>('ORDER_SUCCESS')
  const [currentTemplate, setCurrentTemplate] = useState<NotificationTemplate | null>(null)
  const [templateVariables, setTemplateVariables] = useState<TemplateVariablesResponse | null>(null)
  const [templateContent, setTemplateContent] = useState('')
  const [testTemplateLoading, setTestTemplateLoading] = useState(false)

  // 加载机器人配置
  useEffect(() => {
    fetchConfigs()
  }, [])

  // 加载模板类型
  useEffect(() => {
    fetchTemplateTypes()
  }, [])

  // 当选中的模板类型改变时，加载模板详情和变量
  useEffect(() => {
    if (selectedTemplateType) {
      fetchTemplateDetail(selectedTemplateType)
      fetchTemplateVariables(selectedTemplateType)
    }
  }, [selectedTemplateType])

  const fetchConfigs = async () => {
    setLoading(true)
    try {
      const response = await apiService.notifications.list({ type: 'telegram' })
      if (response.data.code === 0 && response.data.data) {
        setConfigs(response.data.data)
      } else {
        message.error(response.data.msg || t('notificationSettings.fetchFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.fetchFailed'))
    } finally {
        setLoading(false)
      }
  }

  const fetchTemplateTypes = async () => {
    try {
      const response = await apiService.notifications.getTemplateTypes()
      if (response.data.code === 0 && response.data.data) {
        setTemplateTypes(response.data.data)
      }
    } catch (error) {
        console.error('获取模板类型失败:', error)
      }
  }

  const fetchTemplateDetail = async (templateType: string) => {
    try {
      const response = await apiService.notifications.getTemplateDetail({ templateType })
      if (response.data.code === 0 && response.data.data) {
        setCurrentTemplate(response.data.data)
        setTemplateContent(response.data.data.templateContent)
      }
    } catch (error) {
        console.error('获取模板详情失败:', error)
      }
  }

  const fetchTemplateVariables = async (templateType: string) => {
    try {
      const response = await apiService.notifications.getTemplateVariables({ templateType })
      if (response.data.code === 0 && response.data.data) {
        setTemplateVariables(response.data.data)
      }
    } catch (error) {
        console.error('获取模板变量失败:', error)
      }
  }

  // 机器人配置相关方法
  const handleCreate = () => {
    setEditingConfig(null)
    form.resetFields()
    form.setFieldsValue({
      type: 'telegram',
      enabled: true,
      config: {
        botToken: '',
        chatIds: []
      }
    })
    setModalVisible(true)
  }

  const handleEdit = (config: NotificationConfig) => {
    setEditingConfig(config)
    let botToken = ''
    let chatIds = ''

    if (config.config) {
      if ('data' in config.config && config.config.data) {
        const data = config.config.data as any
        botToken = data.botToken || ''
        if (data.chatIds) {
          if (Array.isArray(data.chatIds)) {
            chatIds = data.chatIds.join(',')
          } else if (typeof data.chatIds === 'string') {
            chatIds = data.chatIds
          }
        }
      } else {
        if ('botToken' in config.config) {
          botToken = (config.config as any).botToken || ''
        }
        if ('chatIds' in config.config) {
          const ids = (config.config as any).chatIds
          if (Array.isArray(ids)) {
            chatIds = ids.join(',')
          } else if (typeof ids === 'string') {
            chatIds = ids
          }
        }
      }
    }

    form.setFieldsValue({
      type: config.type,
      name: config.name,
      enabled: config.enabled,
      config: {
        botToken: botToken,
        chatIds: chatIds
      }
    })
    setModalVisible(true)
  }

  const handleDelete = async (id: number) => {
    try {
      const response = await apiService.notifications.delete({ id })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.deleteSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.deleteFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.deleteFailed'))
    }
  }

  const handleUpdateEnabled = async (id: number, enabled: boolean) => {
    try {
      const response = await apiService.notifications.updateEnabled({ id, enabled })
      if (response.data.code === 0) {
        message.success(enabled ? t('notificationSettings.enableSuccess') : t('notificationSettings.disableSuccess'))
        fetchConfigs()
      } else {
        message.error(response.data.msg || t('notificationSettings.updateStatusFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.updateStatusFailed'))
    }
  }

  const handleTest = async () => {
    setTestLoading(true)
    try {
      const response = await apiService.notifications.test({ message: '这是一条测试消息' })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.testFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.testFailed'))
    } finally {
      setTestLoading(false)
    }
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      const chatIds = typeof values.config.chatIds === 'string'
        ? values.config.chatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
        : values.config.chatIds || []

      const configData: NotificationConfigRequest | NotificationConfigUpdateRequest = {
        type: values.type,
        name: values.name,
        enabled: values.enabled,
        config: {
          botToken: values.config.botToken,
          chatIds: chatIds
        }
      }

      if (editingConfig?.id) {
        const updateData = {
          ...configData,
          id: editingConfig.id
        } as NotificationConfigUpdateRequest
        const response = await apiService.notifications.update(updateData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.updateSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.updateFailed'))
        }
      } else {
        const response = await apiService.notifications.create(configData)
        if (response.data.code === 0) {
          message.success(t('notificationSettings.createSuccess'))
          setModalVisible(false)
          fetchConfigs()
        } else {
          message.error(response.data.msg || t('notificationSettings.createFailed'))
        }
      }
    } catch (error: any) {
      if (error.errorFields) {
        return
      }
      message.error(error.message || t('message.error'))
    }
  }

  const getConfigFormComponent = (type: string) => {
    switch (type?.toLowerCase()) {
      case 'telegram':
        return <TelegramConfigForm form={form} />
      default:
        return null
    }
  }

  // 模板配置相关方法
  const handleTemplateTypeChange = (type: string) => {
    setSelectedTemplateType(type)
  }

  const handleTemplateContentChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setTemplateContent(e.target.value)
  }

  const handleSaveTemplate = async () => {
    try {
      const response = await apiService.notifications.updateTemplate({
        templateType: selectedTemplateType,
        templateContent: templateContent
      })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.templates.saveSuccess'))
        fetchTemplateDetail(selectedTemplateType)
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.saveFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.templates.saveFailed'))
    }
  }

  const handleResetTemplate = async () => {
    try {
      const response = await apiService.notifications.resetTemplate({
        templateType: selectedTemplateType
      })
      if (response.data.code === 0) {
        message.success(t('notificationSettings.templates.resetSuccess'))
        fetchTemplateDetail(selectedTemplateType)
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.resetFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.templates.resetFailed'))
    }
  }

  const handleTestTemplate = async () => {
    setTestTemplateLoading(true)
    try {
      const response = await apiService.notifications.testTemplate({
        templateType: selectedTemplateType,
        templateContent: templateContent
      })
      if (response.data.code === 0 && response.data.data) {
        message.success(t('notificationSettings.templates.testSuccess'))
      } else {
        message.error(response.data.msg || t('notificationSettings.templates.testFailed'))
      }
    } catch (error: any) {
      message.error(error.message || t('notificationSettings.templates.testFailed'))
    } finally {
      setTestTemplateLoading(false)
    }
  }

  const handleCopyVariable = useCallback((variable: string) => {
    navigator.clipboard.writeText(`{{${variable}}}`)
    message.success(t('notificationSettings.templates.copied'))
  }, [t])

  const [variableHoverKey, setVariableHoverKey] = useState<string | null>(null)

  const renderVariableItem = (variable: TemplateVariable) => {
    const isHover = variableHoverKey === variable.key
    const label = t(`notificationSettings.templates.variableLabels.${variable.key}`)
    const description = t(`notificationSettings.templates.variableDescriptions.${variable.key}`)
    return (
      <Tooltip key={variable.key} title={description || `{{${variable.key}}}`} placement="top">
        <span
          role="button"
          tabIndex={0}
          style={{ ...variableTagStyle, ...(isHover ? variableTagHoverStyle : {}) }}
          onClick={() => handleCopyVariable(variable.key)}
          onMouseEnter={() => setVariableHoverKey(variable.key)}
          onMouseLeave={() => setVariableHoverKey(null)}
          onKeyDown={(e) => e.key === 'Enter' && handleCopyVariable(variable.key)}
        >
          <CopyOutlined style={{ marginRight: 4, fontSize: 11, opacity: 0.6 }} />
          <span style={{ fontFamily: 'monospace' }}>{label}</span>
        </span>
      </Tooltip>
    )
  }

  const renderVariablesPanel = () => {
    if (!templateVariables) return null

    return (
      <Card
        size="small"
        title={
          <span style={{ fontSize: 13, fontWeight: 500 }}>
            {t('notificationSettings.templates.variables')}
          </span>
        }
        style={{ height: '100%', borderRadius: 8 }}
        bodyStyle={{ padding: '12px 16px', maxHeight: 420, overflowY: 'auto' }}
      >
        {templateVariables.categories.map(category => {
          const categoryVariables = templateVariables.variables.filter(v => v.category === category.key)
          if (categoryVariables.length === 0) return null
          return (
            <div key={category.key} style={{ marginBottom: 16 }}>
              <Text type="secondary" style={{ marginBottom: 8, display: 'block', fontSize: 12 }}>
                {t(CATEGORY_LABELS[category.key])}
              </Text>
              <div style={{ display: 'flex', flexWrap: 'wrap' }}>
                {categoryVariables.sort((a, b) => a.sortOrder - b.sortOrder).map(renderVariableItem)}
              </div>
            </div>
          )
        })}
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 11, textAlign: 'center' }}>
          {t('notificationSettings.templates.clickToCopy')}
        </Paragraph>
      </Card>
    )
  }

  // 机器人配置表格列
  const configColumns = [
    {
      title: t('notificationSettings.configName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('notificationSettings.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color="blue">{type.toUpperCase()}</Tag>
    },
    {
      title: t('notificationSettings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'green' : 'default'}>
          {enabled ? t('notificationSettings.enabledStatus') : t('notificationSettings.disabledStatus')}
        </Tag>
      )
    },
    {
      title: t('notificationSettings.chatIds'),
      key: 'chatIds',
      render: (_: any, record: NotificationConfig) => {
        let chatIds: string[] = []
        if (record.config) {
          if ('data' in record.config && record.config.data) {
            const data = (record.config as any).data
            if (data.chatIds) {
              if (Array.isArray(data.chatIds)) {
                chatIds = data.chatIds.filter((id: any) => id && String(id).trim())
              } else if (typeof data.chatIds === 'string') {
                chatIds = data.chatIds.split(',').map((id: string) => id.trim()).filter((id: string) => id)
              }
            }
          } else if ('chatIds' in record.config) {
            const ids = (record.config as any).chatIds
            if (Array.isArray(ids)) {
              chatIds = ids.filter((id: any) => id && String(id).trim())
            } else if (typeof ids === 'string') {
              chatIds = (ids as string).split(',').map((id: string) => id.trim()).filter((id: string) => id)
            }
          }
        }
        return chatIds.length > 0 ? (
          <Text type="secondary" style={{ fontSize: '12px' }}>
            {chatIds.join(', ')}
          </Text>
        ) : (
          <Text type="danger" style={{ fontSize: '12px' }}>{t('notificationSettings.chatIdsNotConfigured')}</Text>
        )
      }
    },
    {
      title: t('common.actions'),
      key: 'action',
      width: isMobile ? 120 : 200,
      render: (_: any, record: NotificationConfig) => (
        <Space size="small" wrap>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
          >
            {t('notificationSettings.edit')}
          </Button>
          <Switch
            checked={record.enabled}
            size="small"
            onChange={(checked) => handleUpdateEnabled(record.id!, checked)}
          />
          <Button
            type="link"
            size="small"
            icon={<SendOutlined />}
            loading={testLoading}
            onClick={handleTest}
          >
            {t('notificationSettings.test')}
          </Button>
          <Popconfirm
            title={t('notificationSettings.deleteConfirm')}
            onConfirm={() => handleDelete(record.id!)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
            >
              {t('notificationSettings.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  const templateTypeTabItems = templateTypes.map(type => ({
    key: type.type,
    label: (
      <Tooltip title={t(`notificationSettings.templateTypeDescriptions.${type.type}`)} placement="top">
        <span>{t(`notificationSettings.templateTypes.${type.type}`)}</span>
      </Tooltip>
    ),
  }))

  return (
    <div>
      <div style={{ marginBottom: '16px' }}>
        <Title level={2} style={{ margin: 0 }}>{t('notificationSettings.title')}</Title>
      </div>

      {/* 机器人配置 */}
      <Card
        title={
          <Space>
            <RobotOutlined />
            <span>{t('notificationSettings.botConfig')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            {t('notificationSettings.addConfig')}
          </Button>
        }
      >
        <Table
          columns={configColumns}
          dataSource={configs}
          loading={loading}
          rowKey="id"
          pagination={false}
          scroll={{ x: isMobile ? 600 : 'auto' }}
        />
      </Card>

      {/* 模板配置 */}
      <Card
        title={
          <Space>
            <FormOutlined />
            <span>{t('notificationSettings.templateConfig')}</span>
          </Space>
        }
        style={{ marginBottom: '16px' }}
      >
        <Tabs
          activeKey={selectedTemplateType}
          onChange={handleTemplateTypeChange}
          items={templateTypeTabItems}
          style={{ marginBottom: 16 }}
          tabBarStyle={{ marginBottom: 0 }}
          type={isMobile ? 'line' : 'card'}
          size={isMobile ? 'small' : 'middle'}
        />
        
        <Row gutter={[16, 16]}>
          <Col xs={24} sm={24} md={17}>
            <Card size="small" bordered={false} style={{ background: '#fafafa', marginBottom: 12, borderRadius: 8 }} bodyStyle={{ padding: '10px 16px' }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', justifyContent: 'space-between' }}>
                <Space wrap size="small">
                  <Text type="secondary" style={{ fontSize: 13 }}>{t('notificationSettings.templates.templateContent')}</Text>
                  {currentTemplate && (
                    <Tag color={currentTemplate.isDefault ? 'green' : 'blue'} style={{ margin: 0 }}>
                      {currentTemplate.isDefault ? t('notificationSettings.templates.isDefault') : t('notificationSettings.templates.isCustom')}
                    </Tag>
                  )}
                </Space>
                <Space wrap size="small">
                  <Popconfirm
                    title={t('notificationSettings.templates.resetConfirm')}
                    onConfirm={handleResetTemplate}
                    okText={t('common.confirm')}
                    cancelText={t('common.cancel')}
                  >
                    <Button size="small" icon={<ReloadOutlined />}>
                      {t('notificationSettings.templates.resetToDefault')}
                    </Button>
                  </Popconfirm>
                  <Button size="small" type="primary" icon={<CheckOutlined />} onClick={handleSaveTemplate}>
                    {t('common.save')}
                  </Button>
                  <Button size="small" icon={<SendOutlined />} loading={testTemplateLoading} onClick={handleTestTemplate}>
                    {t('notificationSettings.test')}
                  </Button>
                </Space>
              </div>
            </Card>
            <TextArea
              value={templateContent}
              onChange={handleTemplateContentChange}
              rows={isMobile ? 10 : 16}
              style={{ fontFamily: 'monospace', fontSize: 13, borderRadius: 8, resize: 'none' }}
              placeholder={t('notificationSettings.templates.contentPlaceholder')}
            />
          </Col>
          <Col xs={24} sm={24} md={7}>
            {renderVariablesPanel()}
          </Col>
        </Row>
      </Card>

      <Modal
        title={editingConfig ? t('notificationSettings.editConfig') : t('notificationSettings.addConfig')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={isMobile ? '90%' : 600}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Form
          form={form}
          layout="vertical"
        >
          <Form.Item
            name="type"
            label={t('notificationSettings.type')}
            rules={[{ required: true, message: t('notificationSettings.typeRequired') }]}
          >
            <Input disabled value="telegram" />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('notificationSettings.configName')}
            rules={[{ required: true, message: t('notificationSettings.configNameRequired') }]}
          >
            <Input placeholder={t('notificationSettings.configNamePlaceholder')} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('notificationSettings.enabled')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item shouldUpdate={(prevValues, currentValues) => {
            return prevValues.type !== currentValues.type ||
                   prevValues.config !== currentValues.config
          }}>
            {() => {
              const currentType = form.getFieldValue('type') || 'telegram'
              return getConfigFormComponent(currentType)
            }}
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default NotificationSettingsPage
