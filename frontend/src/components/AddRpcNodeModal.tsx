import { useState } from 'react'
import { Modal, Form, Input, Select, message, Space } from 'antd'
import { LinkOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import type { RpcNodeAddRequest } from '../types'
import { useTranslation } from 'react-i18next'

interface AddRpcNodeModalProps {
    visible: boolean
    onCancel: () => void
    onSuccess: () => void
}

const { Option } = Select

const AddRpcNodeModal: React.FC<AddRpcNodeModalProps> = ({ visible, onCancel, onSuccess }) => {
    const { t } = useTranslation()
    const [form] = Form.useForm()
    const [selectedProvider, setSelectedProvider] = useState<string>('CUSTOM')
    const [validating, setValidating] = useState(false)

    const providerOptions = [
        { value: 'ALCHEMY', label: t('rpcNodeSettings.providerAlchemy'), url: 'https://dashboard.alchemy.com/' },
        { value: 'INFURA', label: t('rpcNodeSettings.providerInfura'), url: 'https://infura.io/' },
        { value: 'QUICKNODE', label: t('rpcNodeSettings.providerQuickNode'), url: 'https://www.quicknode.com/' },
        { value: 'CHAINSTACK', label: t('rpcNodeSettings.providerChainstack'), url: 'https://chainstack.com/' },
        { value: 'GETBLOCK', label: t('rpcNodeSettings.providerGetBlock'), url: 'https://getblock.io/' },
        { value: 'CUSTOM', label: t('rpcNodeSettings.customNode'), url: '' }
    ]

    const handleSubmit = async () => {
        try {
            const values = await form.validateFields()
            setValidating(true)

            const request: RpcNodeAddRequest = {
                providerType: values.providerType,
                name: values.name,
                apiKey: values.apiKey,
                httpUrl: values.httpUrl,
                wsUrl: values.wsUrl
            }

            // 先验证节点
            const validateResponse = await apiService.rpcNodes.validate(request)

            if (validateResponse.data.code === 0 && validateResponse.data.data) {
                const result = validateResponse.data.data

                if (!result.valid) {
                    message.error(`${t('rpcNodeSettings.validateFailed')} ${result.message}`)
                    setValidating(false)
                    return
                }

                // 验证通过,添加节点
                const addResponse = await apiService.rpcNodes.add(request)

                if (addResponse.data.code === 0) {
                    message.success(t('rpcNodeSettings.addSuccess'))
                    form.resetFields()
                    setSelectedProvider('CUSTOM')
                    onSuccess()
                } else {
                    message.error(addResponse.data.msg || t('rpcNodeSettings.addFailed'))
                }
            } else {
                message.error(validateResponse.data.msg || t('rpcNodeSettings.validateError'))
            }
        } catch (error: any) {
            if (!error.errorFields) {
                message.error(error.message || t('rpcNodeSettings.operationFailed'))
            }
        } finally {
            setValidating(false)
        }
    }

    const handleCancel = () => {
        form.resetFields()
        setSelectedProvider('CUSTOM')
        onCancel()
    }

    const currentProvider = providerOptions.find(p => p.value === selectedProvider)

    return (
        <Modal
            title={t('rpcNodeSettings.addNodeTitle')}
            open={visible}
            onOk={handleSubmit}
            onCancel={handleCancel}
            width={600}
            confirmLoading={validating}
            okText={t('rpcNodeSettings.validateAndAdd')}
            cancelText={t('common.cancel')}
        >
            <Form
                form={form}
                layout="vertical"
                initialValues={{ providerType: 'CUSTOM' }}
            >
                <Form.Item
                    label={t('rpcNodeSettings.providerTypeLabel')}
                    name="providerType"
                    rules={[{ required: true, message: t('rpcNodeSettings.providerTypeRequired') }]}
                >
                    <Select onChange={setSelectedProvider}>
                        {providerOptions.map(opt => (
                            <Option key={opt.value} value={opt.value}>{opt.label}</Option>
                        ))}
                    </Select>
                </Form.Item>

                <Form.Item
                    label={t('rpcNodeSettings.nodeNameLabel')}
                    name="name"
                    rules={[{ required: true, message: t('rpcNodeSettings.nodeNameRequired') }]}
                >
                    <Input placeholder={t('rpcNodeSettings.nodeNamePlaceholder')} />
                </Form.Item>

                {selectedProvider !== 'CUSTOM' && (
                    <Form.Item
                        label={
                            <Space>
                                <span>{t('rpcNodeSettings.apiKeyLabel')}</span>
                                {currentProvider?.url && (
                                    <a href={currentProvider.url} target="_blank" rel="noopener noreferrer">
                                        <LinkOutlined /> {t('rpcNodeSettings.getApiKey')}
                                    </a>
                                )}
                            </Space>
                        }
                        name="apiKey"
                        rules={[{ required: true, message: t('rpcNodeSettings.apiKeyRequired') }]}
                    >
                        <Input.Password
                            placeholder={t('rpcNodeSettings.apiKeyPlaceholder')}
                            autoComplete="off"
                        />
                    </Form.Item>
                )}

                {selectedProvider === 'CUSTOM' && (
                    <>
                        <Form.Item
                            label={t('rpcNodeSettings.httpUrlLabel')}
                            name="httpUrl"
                            rules={[
                                { required: true, message: t('rpcNodeSettings.httpUrlRequired') },
                                { type: 'url', message: t('rpcNodeSettings.httpUrlInvalid') }
                            ]}
                        >
                            <Input placeholder={t('rpcNodeSettings.httpUrlPlaceholder')} />
                        </Form.Item>

                        <Form.Item
                            label={t('rpcNodeSettings.wsUrlLabel')}
                            name="wsUrl"
                            rules={[{ type: 'url', message: t('rpcNodeSettings.wsUrlInvalid') }]}
                        >
                            <Input placeholder={t('rpcNodeSettings.wsUrlPlaceholder')} />
                        </Form.Item>
                    </>
                )}
            </Form>
        </Modal>
    )
}

export default AddRpcNodeModal
