import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Button, Switch, message, Typography, Space, Radio, InputNumber, Modal, Table, Select, Divider, Input } from 'antd'
import { ArrowLeftOutlined, SaveOutlined, FileTextOutlined, PlusOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { Leader, CopyTradingTemplate, CopyTradingCreateRequest } from '../types'
import { formatUSDC } from '../utils'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import AccountImportForm from '../components/AccountImportForm'
import LeaderAddForm from '../components/LeaderAddForm'

const { Title } = Typography
const { Option } = Select

const CopyTradingAdd: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [templateModalVisible, setTemplateModalVisible] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  
  // 导入账户modal相关状态
  const [accountImportModalVisible, setAccountImportModalVisible] = useState(false)
  const [accountImportForm] = Form.useForm()
  
  // 添加leader modal相关状态
  const [leaderAddModalVisible, setLeaderAddModalVisible] = useState(false)
  const [leaderAddForm] = Form.useForm()
  
  // 生成默认配置名
  const generateDefaultConfigName = (): string => {
    const now = new Date()
    const dateStr = now.toLocaleDateString('zh-CN', { 
      year: 'numeric', 
      month: '2-digit', 
      day: '2-digit' 
    }).replace(/\//g, '-')
    const timeStr = now.toLocaleTimeString('zh-CN', { 
      hour: '2-digit', 
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
    return `跟单配置-${dateStr}-${timeStr}`
  }
  
  useEffect(() => {
    fetchAccounts()
    fetchLeaders()
    fetchTemplates()
    
    // 生成默认配置名
    const defaultConfigName = generateDefaultConfigName()
    form.setFieldsValue({ configName: defaultConfigName })
  }, [])
  
  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchLeaderFailed') || '获取 Leader 列表失败')
    }
  }
  
  const fetchTemplates = async () => {
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.fetchTemplateFailed') || '获取模板列表失败')
    }
  }
  
  const handleSelectTemplate = (template: CopyTradingTemplate) => {
    // 填充模板数据到表单（只填充模板中存在的字段）
    form.setFieldsValue({
      copyMode: template.copyMode,
      copyRatio: template.copyRatio ? parseFloat(template.copyRatio) * 100 : 100, // 转换为百分比显示
      fixedAmount: template.fixedAmount ? parseFloat(template.fixedAmount) : undefined,
      maxOrderSize: template.maxOrderSize ? parseFloat(template.maxOrderSize) : undefined,
      minOrderSize: template.minOrderSize ? parseFloat(template.minOrderSize) : undefined,
      maxDailyOrders: template.maxDailyOrders,
      priceTolerance: template.priceTolerance ? parseFloat(template.priceTolerance) : undefined,
      supportSell: template.supportSell,
      minOrderDepth: template.minOrderDepth ? parseFloat(template.minOrderDepth) : undefined,
      maxSpread: template.maxSpread ? parseFloat(template.maxSpread) : undefined,
      minPrice: template.minPrice ? parseFloat(template.minPrice) : undefined,
      maxPrice: template.maxPrice ? parseFloat(template.maxPrice) : undefined,
      maxPositionValue: (template as any).maxPositionValue ? parseFloat((template as any).maxPositionValue) : undefined,
      maxPositionCount: (template as any).maxPositionCount
    })
    setCopyMode(template.copyMode)
    setTemplateModalVisible(false)
    message.success(t('copyTradingAdd.templateFilled') || '模板内容已填充，您可以修改')
  }
  
  const handleCopyModeChange = (mode: 'RATIO' | 'FIXED') => {
    setCopyMode(mode)
  }
  
  // 处理导入账户成功
  const handleAccountImportSuccess = async (accountId: number) => {
    message.success(t('accountImport.importSuccess'))
    
    // 刷新账户列表
    await fetchAccounts()
    
    // 自动选择新添加的账户
    form.setFieldsValue({ accountId })
    
    // 关闭modal并重置表单
    setAccountImportModalVisible(false)
    accountImportForm.resetFields()
  }
  
  // 处理添加leader成功
  const handleLeaderAddSuccess = async (leaderId: number) => {
    message.success(t('leaderAdd.addSuccess') || '添加 Leader 成功')
    
    // 刷新leader列表
    await fetchLeaders()
    
    // 自动选择新添加的leader
    form.setFieldsValue({ leaderId })
    
    // 关闭modal并重置表单
    setLeaderAddModalVisible(false)
    leaderAddForm.resetFields()
  }
  
  const handleSubmit = async (values: any) => {
    // 前端校验
    if (values.copyMode === 'FIXED') {
      if (!values.fixedAmount || Number(values.fixedAmount) < 1) {
        message.error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1')
        return
      }
    }
    
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && Number(values.minOrderSize) < 1) {
      message.error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1')
      return
    }
    
    setLoading(true)
    try {
      const request: CopyTradingCreateRequest = {
        accountId: values.accountId,
        leaderId: values.leaderId,
        enabled: true, // 默认启用
        copyMode: values.copyMode || 'RATIO',
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.maxOrderSize?.toString(),
        minOrderSize: values.minOrderSize?.toString(),
        maxDailyLoss: values.maxDailyLoss?.toString(),
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        delaySeconds: values.delaySeconds,
        pollIntervalSeconds: values.pollIntervalSeconds,
        useWebSocket: values.useWebSocket,
        websocketReconnectInterval: values.websocketReconnectInterval,
        websocketMaxRetries: values.websocketMaxRetries,
        supportSell: values.supportSell !== false,
        minOrderDepth: values.minOrderDepth?.toString(),
        maxSpread: values.maxSpread?.toString(),
        minPrice: values.minPrice?.toString(),
        maxPrice: values.maxPrice?.toString(),
        maxPositionValue: values.maxPositionValue?.toString(),
        maxPositionCount: values.maxPositionCount,
        configName: values.configName?.trim(),
        pushFailedOrders: values.pushFailedOrders ?? false
      }
      
      const response = await apiService.copyTrading.create(request)
      
      if (response.data.code === 0) {
        message.success(t('copyTradingAdd.createSuccess') || '创建跟单配置成功')
        navigate('/copy-trading')
      } else {
        message.error(response.data.msg || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
      }
    } catch (error: any) {
      message.error(error.message || t('copyTradingAdd.createFailed') || '创建跟单配置失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/copy-trading')}
        >
          {t('common.back') || '返回'}
        </Button>
      </div>
      
      <Card>
        <Title level={4}>{t('copyTradingAdd.title') || '新增跟单配置'}</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100,
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyLoss: 10000,
            maxDailyOrders: 100,
            priceTolerance: 5,
            delaySeconds: 0,
            pollIntervalSeconds: 5,
            useWebSocket: true,
            websocketReconnectInterval: 5000,
            websocketMaxRetries: 10,
            supportSell: true,
            pushFailedOrders: false
          }}
        >
          {/* 基础信息 */}
          <Form.Item
            label={t('copyTradingAdd.configName') || '配置名'}
            name="configName"
            rules={[
              { required: true, message: t('copyTradingAdd.configNameRequired') || '请输入配置名' },
              { whitespace: true, message: t('copyTradingAdd.configNameRequired') || '配置名不能为空' }
            ]}
            tooltip={t('copyTradingAdd.configNameTooltip') || '为跟单配置设置一个名称，便于识别和管理'}
          >
            <Input 
              placeholder={t('copyTradingAdd.configNamePlaceholder') || '例如：跟单配置1'} 
              maxLength={255}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectWallet') || '选择钱包'}
            name="accountId"
            rules={[{ required: true, message: t('copyTradingAdd.walletRequired') || '请选择钱包' }]}
          >
            <Select 
              placeholder={t('copyTradingAdd.selectWalletPlaceholder') || '请选择钱包'}
              notFoundContent={
                accounts.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noAccounts') || '暂无账户'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setAccountImportModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.importAccount') || '导入账户'}
                    </Button>
                  </div>
                ) : null
              }
            >
              {accounts.map(account => (
                <Option key={account.id} value={account.id}>
                  {account.accountName || `账户 ${account.id}`} ({account.walletAddress.slice(0, 6)}...{account.walletAddress.slice(-4)})
                </Option>
              ))}
            </Select>
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.selectLeader') || '选择 Leader'}
            name="leaderId"
            rules={[{ required: true, message: t('copyTradingAdd.leaderRequired') || '请选择 Leader' }]}
          >
            <Select 
              placeholder={t('copyTradingAdd.selectLeaderPlaceholder') || '请选择 Leader'}
              notFoundContent={
                leaders.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '12px' }}>
                    <div style={{ marginBottom: '8px' }}>{t('copyTradingAdd.noLeaders') || '暂无 Leader'}</div>
                    <Button 
                      type="primary" 
                      icon={<PlusOutlined />}
                      onClick={() => setLeaderAddModalVisible(true)}
                      size="small"
                    >
                      {t('copyTradingAdd.addLeader') || '添加 Leader'}
                    </Button>
                  </div>
                ) : null
              }
            >
              {leaders.map(leader => (
                <Option key={leader.id} value={leader.id}>
                  {leader.leaderName || `Leader ${leader.id}`} ({leader.leaderAddress.slice(0, 6)}...{leader.leaderAddress.slice(-4)})
                </Option>
              ))}
            </Select>
          </Form.Item>
          
          {/* 模板填充按钮 */}
          <Form.Item>
            <Button
              type="dashed"
              icon={<FileTextOutlined />}
              onClick={() => setTemplateModalVisible(true)}
              style={{ width: '100%' }}
            >
              {t('copyTradingAdd.selectTemplateFromModal') || '从模板填充配置'}
            </Button>
          </Form.Item>
          
          {/* 跟单金额模式 */}
          <Form.Item
            label={t('copyTradingAdd.copyMode') || '跟单金额模式'}
            name="copyMode"
            tooltip={t('copyTradingAdd.copyModeTooltip') || '选择跟单金额的计算方式。比例模式：跟单金额随 Leader 订单大小按比例变化；固定金额模式：无论 Leader 订单大小如何，跟单金额都固定不变。'}
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => handleCopyModeChange(e.target.value)}>
              <Radio value="RATIO">{t('copyTradingAdd.ratioMode') || '比例模式'}</Radio>
              <Radio value="FIXED">{t('copyTradingAdd.fixedAmountMode') || '固定金额模式'}</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label={t('copyTradingAdd.copyRatio') || '跟单比例'}
              name="copyRatio"
              tooltip={t('copyTradingAdd.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
            >
              <InputNumber
                min={10}
                max={1000}
                step={1}
                precision={0}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder={t('copyTradingAdd.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
              />
            </Form.Item>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label={t('copyTradingAdd.fixedAmount') || '固定跟单金额 (USDC)'}
              name="fixedAmount"
              rules={[
                { required: true, message: t('copyTradingAdd.fixedAmountRequired') || '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error(t('copyTradingAdd.invalidNumber') || '请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.fixedAmountMin') || '固定金额必须 >= 1'))
                      }
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
                placeholder={t('copyTradingAdd.fixedAmountPlaceholder') || '固定金额，不随 Leader 订单大小变化，必须 >= 1'}
              />
            </Form.Item>
          )}
          
          {copyMode === 'RATIO' && (
            <>
              <Form.Item
                label={t('copyTradingAdd.maxOrderSize') || '单笔订单最大金额 (USDC)'}
                name="maxOrderSize"
                tooltip={t('copyTradingAdd.maxOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最大金额上限'}
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder={t('copyTradingAdd.maxOrderSizePlaceholder') || '仅在比例模式下生效（可选）'}
                />
              </Form.Item>
              
              <Form.Item
                label={t('copyTradingAdd.minOrderSize') || '单笔订单最小金额 (USDC)'}
                name="minOrderSize"
                tooltip={t('copyTradingAdd.minOrderSizeTooltip') || '比例模式下，限制单笔跟单订单的最小金额下限，必须 >= 1'}
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve()
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error(t('copyTradingAdd.minOrderSizeMin') || '最小金额必须 >= 1'))
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
                  placeholder={t('copyTradingAdd.minOrderSizePlaceholder') || '仅在比例模式下生效，必须 >= 1（可选）'}
                />
              </Form.Item>
            </>
          )}
          
          <Form.Item
            label={t('copyTradingAdd.maxDailyLoss') || '每日最大亏损限制 (USDC)'}
            name="maxDailyLoss"
            tooltip={t('copyTradingAdd.maxDailyLossTooltip') || '限制每日最大亏损金额，用于风险控制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyLossPlaceholder') || '默认 10000 USDC（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxDailyOrders') || '每日最大跟单订单数'}
            name="maxDailyOrders"
            tooltip={t('copyTradingAdd.maxDailyOrdersTooltip') || '限制每日最多跟单的订单数量'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxDailyOrdersPlaceholder') || '默认 100（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.priceTolerance') || '价格容忍度 (%)'}
            name="priceTolerance"
            tooltip={t('copyTradingAdd.priceToleranceTooltip') || '允许跟单价格在 Leader 价格基础上的调整范围'}
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.priceTolerancePlaceholder') || '默认 5%（可选）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.delaySeconds') || '跟单延迟 (秒)'}
            name="delaySeconds"
            tooltip={t('copyTradingAdd.delaySecondsTooltip') || '跟单延迟时间，0 表示立即跟单'}
          >
            <InputNumber
              min={0}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.delaySecondsPlaceholder') || '默认 0（立即跟单）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.minOrderDepth') || '最小订单深度 (USDC)'}
            name="minOrderDepth"
            tooltip={t('copyTradingAdd.minOrderDepthTooltip') || '检查订单簿的总订单金额（买盘+卖盘），确保市场有足够的流动性。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.minOrderDepthPlaceholder') || '例如：100（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxSpread') || '最大价差（绝对价格）'}
            name="maxSpread"
            tooltip={t('copyTradingAdd.maxSpreadTooltip') || '最大价差（绝对价格）。避免在价差过大的市场跟单。不填写则不启用此过滤'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxSpreadPlaceholder') || '例如：0.05（5美分，可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.priceRangeFilter') || '价格区间过滤'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.priceRange') || '价格区间'}
            name="priceRange"
            tooltip={t('copyTradingAdd.priceRangeTooltip') || '配置价格区间，仅在指定价格区间内的订单才会下单。例如：0.11-0.89 表示区间在0.11和0.89之间；-0.89 表示0.89以下都可以；0.11- 表示0.11以上都可以'}
          >
            <Input.Group compact style={{ display: 'flex' }}>
              <Form.Item name="minPrice" noStyle>
                <InputNumber
                  min={0.01}
                  max={0.99}
                  step={0.0001}
                  precision={4}
                  style={{ width: '50%' }}
                  placeholder={t('copyTradingAdd.minPricePlaceholder') || '最低价（可选）'}
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
                  placeholder={t('copyTradingAdd.maxPricePlaceholder') || '最高价（可选）'}
                />
              </Form.Item>
            </Input.Group>
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.positionLimitFilter') || '最大仓位限制'}</Divider>
          
          <Form.Item
            label={t('copyTradingAdd.maxPositionValue') || '最大仓位金额 (USDC)'}
            name="maxPositionValue"
            tooltip={t('copyTradingAdd.maxPositionValueTooltip') || '限制单个市场的最大仓位金额。如果该市场的当前仓位金额 + 跟单金额超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={0}
              step={0.0001}
              precision={4}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionValuePlaceholder') || '例如：100（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Form.Item
            label={t('copyTradingAdd.maxPositionCount') || '最大仓位数量'}
            name="maxPositionCount"
            tooltip={t('copyTradingAdd.maxPositionCountTooltip') || '限制单个市场的最大仓位数量。如果该市场的当前仓位数量达到或超过此限制，则不会下单。不填写则不启用此限制'}
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder={t('copyTradingAdd.maxPositionCountPlaceholder') || '例如：10（可选，不填写表示不启用）'}
            />
          </Form.Item>
          
          <Divider>{t('copyTradingAdd.advancedSettings') || '高级设置'}</Divider>
          
          {/* 跟单卖出 */}
          <Form.Item
            label={t('copyTradingAdd.supportSell') || '跟单卖出'}
            name="supportSell"
            tooltip={t('copyTradingAdd.supportSellTooltip') || '是否跟单 Leader 的卖出订单'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          {/* 推送失败订单 */}
          <Form.Item
            label={t('copyTradingAdd.pushFailedOrders') || '推送失败订单'}
            name="pushFailedOrders"
            tooltip={t('copyTradingAdd.pushFailedOrdersTooltip') || '开启后，失败的订单会推送到 Telegram'}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                icon={<SaveOutlined />}
                loading={loading}
              >
                {t('copyTradingAdd.create') || '创建跟单配置'}
              </Button>
              <Button onClick={() => navigate('/copy-trading')}>
                {t('common.cancel') || '取消'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      
      {/* 模板选择 Modal */}
      <Modal
        title={t('copyTradingAdd.selectTemplate') || '选择模板'}
        open={templateModalVisible}
        onCancel={() => setTemplateModalVisible(false)}
        footer={null}
        width={800}
      >
        <Table
          dataSource={templates}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          onRow={(record) => ({
            onClick: () => handleSelectTemplate(record),
            style: { cursor: 'pointer' }
          })}
          columns={[
            {
              title: t('copyTradingAdd.templateName') || '模板名称',
              dataIndex: 'templateName',
              key: 'templateName'
            },
            {
              title: t('copyTradingAdd.copyMode') || '跟单模式',
              key: 'copyMode',
              render: (_: any, record: CopyTradingTemplate) => (
                <span>
                  {record.copyMode === 'RATIO' 
                    ? `${t('copyTradingAdd.ratioMode') || '比例'} ${record.copyRatio}x`
                    : `${t('copyTradingAdd.fixedAmountMode') || '固定'} ${formatUSDC(record.fixedAmount || '0')} USDC`
                  }
                </span>
              )
            },
            {
              title: t('copyTradingAdd.supportSell') || '跟单卖出',
              dataIndex: 'supportSell',
              key: 'supportSell',
              render: (supportSell: boolean) => supportSell ? (t('common.yes') || '是') : (t('common.no') || '否')
            }
          ]}
        />
      </Modal>
      
      {/* 导入账户 Modal */}
      <Modal
        title={t('accountImport.title') || '导入账户'}
        open={accountImportModalVisible}
        onCancel={() => {
          setAccountImportModalVisible(false)
          accountImportForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 150px)', overflow: 'auto' }}
        destroyOnClose
        maskClosable
        closable
      >
        <AccountImportForm
          form={accountImportForm}
          onSuccess={handleAccountImportSuccess}
          onCancel={() => {
            setAccountImportModalVisible(false)
            accountImportForm.resetFields()
          }}
          showAlert={true}
          showCancelButton={true}
        />
      </Modal>
      
      {/* 添加 Leader Modal */}
      <Modal
        title={t('leaderAdd.title') || '添加 Leader'}
        open={leaderAddModalVisible}
        onCancel={() => {
          setLeaderAddModalVisible(false)
          leaderAddForm.resetFields()
        }}
        footer={null}
        width={isMobile ? '95%' : 600}
        style={{ top: isMobile ? 20 : 50 }}
        bodyStyle={{ padding: '24px', maxHeight: 'calc(100vh - 150px)', overflow: 'auto' }}
        destroyOnClose
        maskClosable
        closable
      >
        <LeaderAddForm
          form={leaderAddForm}
          onSuccess={handleLeaderAddSuccess}
          onCancel={() => {
            setLeaderAddModalVisible(false)
            leaderAddForm.resetFields()
          }}
          showCancelButton={true}
        />
      </Modal>
    </div>
  )
}

export default CopyTradingAdd
