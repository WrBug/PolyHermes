import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message, Input, Modal, Form, Radio, InputNumber, Switch, Divider, Spin, Empty, List, Tooltip } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { apiService } from '../services/api'
import type { CopyTradingTemplate } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Search } = Input

const TemplateList: React.FC = () => {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [copyModalVisible, setCopyModalVisible] = useState(false)
  const [copyForm] = Form.useForm()
  const [copyLoading, setCopyLoading] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  const [_sourceTemplate, setSourceTemplate] = useState<CopyTradingTemplate | null>(null) // 用于跟踪复制的源模板
  
  useEffect(() => {
    fetchTemplates()
  }, [])
  
  const fetchTemplates = async () => {
    setLoading(true)
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      } else {
        message.error(response.data.msg || t('templateList.fetchFailed') || '获取模板列表失败')
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.fetchFailed') || '获取模板列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const handleDelete = async (templateId: number) => {
    try {
      const response = await apiService.templates.delete({ templateId })
      if (response.data.code === 0) {
        message.success(t('templateList.deleteSuccess') || '删除模板成功')
        fetchTemplates()
      } else {
        message.error(response.data.msg || t('templateList.deleteFailed') || '删除模板失败')
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.deleteFailed') || '删除模板失败')
    }
  }
  
  const handleCopy = (template: CopyTradingTemplate) => {
    setSourceTemplate(template)
    setCopyMode(template.copyMode)
    
    // 填充表单数据
    copyForm.setFieldsValue({
      templateName: `${template.templateName}-${t('templateList.copySuffix') || '副本'}`,
      copyMode: template.copyMode,
      copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100,
      fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
      maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
      minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
      maxDailyOrders: template.maxDailyOrders,
      priceTolerance: parseFloat(template.priceTolerance),
      supportSell: template.supportSell,
      pushFilteredOrders: template.pushFilteredOrders ?? false,
      minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
      maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
      minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
      maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined
    })
    
    setCopyModalVisible(true)
  }
  
  const handleCopySubmit = async (values: any) => {
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error(t('templateList.minAmountError') || '最小金额必须 >= 1')
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error(t('templateList.fixedAmountRequired') || '请输入固定跟单金额')
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error(t('templateList.invalidNumber') || '请输入有效的数字')
        return
      }
      if (amount < 1) {
        message.error(t('templateList.fixedAmountError') || '固定金额必须 >= 1，请重新输入')
        return
      }
    }
    
    setCopyLoading(true)
    try {
      const response = await apiService.templates.create({
        templateName: values.templateName,
        copyMode: values.copyMode || 'RATIO',
        // 将百分比转换为小数：100% -> 1.0
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.copyMode === 'RATIO' ? values.maxOrderSize?.toString() : undefined,
        minOrderSize: values.copyMode === 'RATIO' ? values.minOrderSize?.toString() : undefined,
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        pushFilteredOrders: values.pushFilteredOrders ?? false
      })
      
      if (response.data.code === 0) {
        message.success(t('templateList.copySuccess') || '复制模板成功')
        setCopyModalVisible(false)
        copyForm.resetFields()
        fetchTemplates()
      } else {
        message.error(response.data.msg || t('templateList.copyFailed') || '复制模板失败')
      }
    } catch (error: any) {
      message.error(error.message || t('templateList.copyFailed') || '复制模板失败')
    } finally {
      setCopyLoading(false)
    }
  }
  
  const handleCopyCancel = () => {
    setCopyModalVisible(false)
    copyForm.resetFields()
    setSourceTemplate(null)
  }
  
  const filteredTemplates = templates.filter(template =>
    template.templateName.toLowerCase().includes(searchText.toLowerCase())
  )
  
  const columns = [
    {
      title: t('templateList.templateName') || '模板名称',
      dataIndex: 'templateName',
      key: 'templateName',
      render: (text: string) => <strong>{text}</strong>
    },
    {
      title: t('templateList.copyMode') || '跟单模式',
      dataIndex: 'copyMode',
      key: 'copyMode',
      render: (mode: string) => (
        <Tag color={mode === 'RATIO' ? 'blue' : 'green'}>
          {mode === 'RATIO' ? t('templateList.ratio') || '比例' : t('templateList.fixedAmount') || '固定金额'}
        </Tag>
      )
    },
    {
      title: t('templateList.copyConfig') || '跟单配置',
      key: 'copyConfig',
      render: (_: any, record: CopyTradingTemplate) => {
        if (record.copyMode === 'RATIO') {
          return `${t('templateList.ratio') || '比例'} ${record.copyRatio}x`
        } else if (record.copyMode === 'FIXED' && record.fixedAmount) {
          return `${t('templateList.fixedAmount') || '固定'} ${formatUSDC(record.fixedAmount)} USDC`
        }
        return '-'
      }
    },
    {
      title: t('templateList.supportSell') || '跟单卖出',
      dataIndex: 'supportSell',
      key: 'supportSell',
      render: (support: boolean) => (
        <Tag color={support ? 'green' : 'red'}>
          {support ? t('common.yes') || '是' : t('common.no') || '否'}
        </Tag>
      )
    },
    {
      title: t('common.createdAt') || '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (timestamp: number) => {
        const date = new Date(timestamp)
        return date.toLocaleString(i18n.language || 'zh-CN', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit'
        })
      },
      sorter: (a: CopyTradingTemplate, b: CopyTradingTemplate) => a.createdAt - b.createdAt,
      defaultSortOrder: 'descend' as const
    },
    {
      title: t('common.actions') || '操作',
      key: 'action',
      width: isMobile ? 120 : 120,
      fixed: 'right' as const,
      render: (_: any, record: CopyTradingTemplate) => (
        <Space size={4}>
          <Tooltip title={t('common.edit') || '编辑'}>
            <div
              onClick={() => navigate(`/templates/edit/${record.id}`)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <EditOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Tooltip title={t('templateList.copy') || '复制'}>
            <div
              onClick={() => handleCopy(record)}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: '32px',
                height: '32px',
                cursor: 'pointer',
                borderRadius: '6px',
                transition: 'background-color 0.2s'
              }}
              onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f0f0f0'}
              onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
            >
              <CopyOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
            </div>
          </Tooltip>

          <Popconfirm
            title={t('templateList.deleteConfirm') || '确定要删除这个模板吗？'}
            description={t('templateList.deleteConfirmDesc') || '删除后无法恢复，请确保没有跟单关系在使用该模板'}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm') || '确定'}
            cancelText={t('common.cancel') || '取消'}
          >
            <Tooltip title={t('common.delete') || '删除'}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: '32px',
                  height: '32px',
                  cursor: 'pointer',
                  borderRadius: '6px',
                  transition: 'background-color 0.2s'
                }}
                onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#fff1f0'}
                onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}
              >
                <DeleteOutlined style={{ fontSize: '16px', color: '#ff4d4f' }} />
              </div>
            </Tooltip>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <h2 style={{ margin: 0, fontSize: isMobile ? '20px' : '24px' }}>{t('templateList.title') || '跟单模板管理'}</h2>
        <Space size={8}>
          <Search
            placeholder={t('templateList.searchPlaceholder') || '搜索模板名称'}
            allowClear
            style={{ width: isMobile ? 120 : 200 }}
            onSearch={setSearchText}
            onChange={(e) => setSearchText(e.target.value)}
          />
          <Tooltip title={t('templateList.addTemplate') || '新增模板'}>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/templates/add')}
              size={isMobile ? 'middle' : 'large'}
              style={{ borderRadius: '8px', height: isMobile ? '40px' : '48px', fontSize: isMobile ? '14px' : '16px' }}
            />
          </Tooltip>
        </Space>
      </div>

      <Card style={{ borderRadius: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', border: '1px solid #e8e8e8' }} bodyStyle={{ padding: isMobile ? '12px' : '24px' }}>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : filteredTemplates.length === 0 ? (
              <Empty description={t('templateList.noData') || '暂无模板数据'} />
            ) : (
              <List
                dataSource={filteredTemplates}
                renderItem={(template) => {
                  return (
                    <Card
                      key={template.id}
                      style={{
                        marginBottom: '10px',
                        borderRadius: '10px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8',
                        overflow: 'hidden'
                      }}
                      bodyStyle={{ padding: '0' }}
                    >
                      {/* 头部区域 - 模板名称 */}
                      <div style={{
                        padding: '10px 12px',
                        background: 'var(--ant-color-primary, #1677ff)',
                        color: '#fff'
                      }}>
                        <div style={{ fontSize: '15px', fontWeight: '600', marginBottom: '2px' }}>
                          {template.templateName}
                        </div>
                        <div style={{ fontSize: '12px', opacity: '0.9' }}>
                          {template.copyMode === 'RATIO' 
                            ? `${t('templateList.ratioMode') || '比例模式'} ${(parseFloat(template.copyRatio || '0') * 100).toFixed(0).replace(/\.0+$/, '')}%`
                            : `${t('templateList.fixedAmountMode') || '固定金额'} ${formatUSDC(template.fixedAmount || '0')} USDC`
                          }
                        </div>
                      </div>

                      {/* 配置信息区域 */}
                      <div style={{
                        padding: '8px 12px',
                        backgroundColor: '#fafafa',
                        borderBottom: '1px solid #f0f0f0',
                        minHeight: '42px',
                        display: 'flex',
                        alignItems: 'center'
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
                          <div>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('templateList.supportSell') || '跟单卖出'}
                            </div>
                            <div style={{ fontSize: '12px', fontWeight: '500' }}>
                              <Tag color={template.supportSell ? 'green' : 'red'} style={{ margin: 0, fontSize: '10px' }}>
                                {template.supportSell ? (t('common.yes') || '是') : (t('common.no') || '否')}
                              </Tag>
                            </div>
                          </div>
                          <div style={{ textAlign: 'right' }}>
                            <div style={{ fontSize: '10px', color: '#8c8c8c' }}>
                              {t('templateList.maxDailyOrders') || '每日最大'}
                            </div>
                            <div style={{ fontSize: '12px', fontWeight: '500', color: '#1890ff' }}>
                              {template.maxDailyOrders} {t('common.orders') || '单'}
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* 金额限制区域（仅比例模式显示） */}
                      {template.copyMode === 'RATIO' && (
                        <div style={{
                          padding: '6px 12px',
                          fontSize: '11px',
                          color: '#8c8c8c',
                          borderBottom: '1px solid #f0f0f0'
                        }}>
                          <span style={{ color: '#d48806' }}>{t('templateList.amountLimit') || '金额限制'}: </span>
                          {template.maxOrderSize && (
                            <span>{t('templateList.max') || '最大'} {formatUSDC(template.maxOrderSize)} USDC</span>
                          )}
                          {template.maxOrderSize && template.minOrderSize && <span> | </span>}
                          {template.minOrderSize && (
                            <span>{t('templateList.min') || '最小'} {formatUSDC(template.minOrderSize)} USDC</span>
                          )}
                          {!template.maxOrderSize && !template.minOrderSize && <span style={{ color: '#bfbfbf' }}>{t('templateList.notSet') || '未设置'}</span>}
                        </div>
                      )}

                      {/* 创建时间 */}
                      <div style={{
                        padding: '6px 12px',
                        fontSize: '11px',
                        color: '#8c8c8c'
                      }}>
                        {t('common.createdAt') || '创建时间'}: {new Date(template.createdAt).toLocaleString(i18n.language || 'zh-CN', {
                          year: 'numeric',
                          month: '2-digit',
                          day: '2-digit',
                          hour: '2-digit',
                          minute: '2-digit'
                        })}
                      </div>

                      {/* 图标操作栏 */}
                      <div style={{
                        padding: '8px 12px',
                        display: 'flex',
                        justifyContent: 'space-around',
                        alignItems: 'center'
                      }}>
                        <Tooltip title={t('common.edit') || '编辑'}>
                          <div
                            onClick={() => navigate(`/templates/edit/${template.id}`)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <EditOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.edit') || '编辑'}</span>
                          </div>
                        </Tooltip>

                        <Tooltip title={t('templateList.copy') || '复制'}>
                          <div
                            onClick={() => handleCopy(template)}
                            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}
                          >
                            <CopyOutlined style={{ fontSize: '18px', color: '#1890ff' }} />
                            <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('templateList.copy') || '复制'}</span>
                          </div>
                        </Tooltip>

                        <Popconfirm
                          title={t('templateList.deleteConfirm') || '确定要删除这个模板吗？'}
                          description={t('templateList.deleteConfirmDesc') || '删除后无法恢复，请确保没有跟单关系在使用该模板'}
                          onConfirm={() => handleDelete(template.id)}
                          okText={t('common.confirm') || '确定'}
                          cancelText={t('common.cancel') || '取消'}
                        >
                          <Tooltip title={t('common.delete') || '删除'}>
                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'pointer', padding: '4px 8px' }}>
                              <DeleteOutlined style={{ fontSize: '18px', color: '#ff4d4f' }} />
                              <span style={{ fontSize: '10px', color: '#8c8c8c', marginTop: '2px' }}>{t('common.delete') || '删除'}</span>
                            </div>
                          </Tooltip>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                }}
              />
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={filteredTemplates}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`
            }}
          />
        )}
      </Card>
      
      <Modal
        title="复制模板"
        open={copyModalVisible}
        onCancel={handleCopyCancel}
        footer={null}
        width={isMobile ? '90%' : 800}
        destroyOnClose
      >
        <Form
          form={copyForm}
          layout="vertical"
          onFinish={handleCopySubmit}
        >
          <Form.Item
            label="模板名称"
            name="templateName"
            tooltip="模板的唯一标识名称，用于区分不同的跟单配置模板。模板名称必须唯一，不能与其他模板重名。"
            rules={[{ required: true, message: '请输入模板名称' }]}
          >
            <Input placeholder="请输入模板名称" />
          </Form.Item>
          
          <Form.Item
            label="跟单金额模式"
            name="copyMode"
            tooltip="选择跟单金额的计算方式。比例模式：跟单金额随 Leader 订单大小按比例变化；固定金额模式：无论 Leader 订单大小如何，跟单金额都固定不变。复制模板时，跟单模式保持原模板设置，不可修改。"
            rules={[{ required: true }]}
          >
            <Radio.Group disabled>
              <Radio value="RATIO">比例模式</Radio>
              <Radio value="FIXED">固定金额模式</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label="跟单比例"
              name="copyRatio"
              tooltip="跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单"
            >
              <InputNumber
                min={0.01}
                max={10000}
                step={0.01}
                precision={2}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder="例如：100 表示 100%（1:1 跟单），默认 100%"
                parser={(value) => {
                  const parsed = parseFloat(value || '0')
                  if (parsed > 10000) return 10000
                  return parsed
                }}
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  if (num > 10000) return '10000'
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label="固定跟单金额 (USDC)"
              name="fixedAmount"
              rules={[
                { required: true, message: '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error('请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error('固定金额必须 >= 1，请重新输入'))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder="固定金额，不随 Leader 订单大小变化，必须 >= 1"
                formatter={(value) => {
                  if (!value && value !== 0) return ''
                  const num = parseFloat(value.toString())
                  if (isNaN(num)) return ''
                  return num.toString().replace(/\.0+$/, '')
                }}
              />
            </Form.Item>
          )}
          
          {copyMode === 'RATIO' && (
            <>
              <Form.Item
                label="单笔订单最大金额 (USDC)"
                name="maxOrderSize"
                tooltip="比例模式下，限制单笔跟单订单的最大金额上限，用于防止跟单金额过大，控制风险。例如：设置为 1000，即使计算出的跟单金额超过 1000，也会限制为 1000 USDC。"
              >
                <InputNumber
                  min={0.01}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder="仅在比例模式下生效（可选）"
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              
              <Form.Item
                label="单笔订单最小金额 (USDC)"
                name="minOrderSize"
                tooltip="比例模式下，限制单笔跟单订单的最小金额下限，用于过滤掉金额过小的订单，避免频繁小额交易。如果填写，必须 >= 1 USDC。例如：设置为 10，如果计算出的跟单金额小于 10，则跳过该订单。"
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error('最小金额必须 >= 1'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber
                  min={1}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder="仅在比例模式下生效，必须 >= 1（可选）"
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            </>
          )}
          
          <Form.Item
            label="每日最大跟单订单数"
            name="maxDailyOrders"
            tooltip="限制每日最多跟单的订单数量，用于风险控制，防止过度交易。例如：设置为 50，当日跟单订单数达到 50 后，停止跟单，次日重置。"
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder="默认 100（可选）"
            />
          </Form.Item>
          
          <Form.Item
            label="价格容忍度 (%)"
            name="priceTolerance"
            tooltip="允许跟单价格在 Leader 价格基础上的调整范围，用于在 Leader 价格 ± 容忍度范围内调整价格，提高成交率。例如：设置为 5%，Leader 价格为 0.5，则跟单价格可在 0.475-0.525 范围内。"
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder="默认 5%（可选）"
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label="跟单卖出"
            name="supportSell"
            tooltip="是否跟单 Leader 的卖出订单。开启：跟单 Leader 的买入和卖出订单；关闭：只跟单 Leader 的买入订单，忽略卖出订单。"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item
            label={t('templateList.pushFilteredOrders') || '推送已过滤订单'}
            name="pushFilteredOrders"
            tooltip={t('templateList.pushFilteredOrdersTooltip') || '开启后，被过滤的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Divider>过滤条件（可选）</Divider>
          
          <Form.Item
            label="最小订单深度 (USDC)"
            name="minOrderDepth"
            tooltip="检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤"
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder="例如：100（可选，不填写表示不启用）"
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Form.Item
            label="最大价差（绝对价格）"
            name="maxSpread"
            tooltip="最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤"
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder="例如：0.05（5美分，可选，不填写表示不启用）"
              formatter={(value) => {
                if (!value && value !== 0) return ''
                const num = parseFloat(value.toString())
                if (isNaN(num)) return ''
                return num.toString().replace(/\.0+$/, '')
              }}
            />
          </Form.Item>
          
          <Divider>价格区间过滤</Divider>
          
          <Form.Item
            label="价格区间"
            name="priceRange"
            tooltip="仅跟单 Leader 交易价格在指定区间内的订单。不填写表示不限制。示例：填写 0.11 和 0.89 表示仅跟单价格在 0.11 到 0.89 之间的订单；只填写最高价 0.89 表示仅跟单价格在 0.89 以下的订单；只填写最低价 0.11 表示仅跟单价格在 0.11 以上的订单。"
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder="最低价（留空不限制）"
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
              <span style={{ display: 'inline-block', width: '20px', textAlign: 'center', lineHeight: '32px' }}>-</span>
              <Form.Item name="maxPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder="最高价（留空不限制）"
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            </Input.Group>
          </Form.Item>
          
          <Form.Item shouldUpdate>
            {({ getFieldsError }) => {
              const errors = getFieldsError()
              const hasErrors = errors.some(({ errors }) => errors && errors.length > 0)
              return (
                <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
                  <Button onClick={handleCopyCancel}>
                    取消
                  </Button>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={copyLoading}
                    disabled={hasErrors}
                  >
                    创建模板
                  </Button>
                </Space>
              )
            }}
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default TemplateList

